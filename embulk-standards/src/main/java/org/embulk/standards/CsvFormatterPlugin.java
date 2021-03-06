package org.embulk.standards;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.spi.type.TimestampType;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampFormatter;
import org.embulk.config.TaskSource;
import org.embulk.config.ConfigSource;
import org.embulk.spi.Column;
import org.embulk.spi.Schema;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.FormatterPlugin;
import org.embulk.spi.Page;
import org.embulk.spi.PageOutput;
import org.embulk.spi.PageReader;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutput;
import org.embulk.spi.util.LineEncoder;

import org.embulk.spi.util.Newline;
import java.util.Map;

public class CsvFormatterPlugin
        implements FormatterPlugin
{
    public enum QuotePolicy
    {
        ALL("ALL"),
        MINIMAL("MINIMAL"),
        NONE("NONE");

        private final String string;

        private QuotePolicy(String string)
        {
            this.string = string;
        }

        public String getString()
        {
            return string;
        }
    }

    public interface PluginTask
            extends LineEncoder.EncoderTask, TimestampFormatter.FormatterTask
    {
        @Config("header_line")
        @ConfigDefault("true")
        public boolean getHeaderLine();

        @Config("delimiter")
        @ConfigDefault("\",\"")
        public char getDelimiterChar();

        @Config("quote")
        @ConfigDefault("\"\\\"\"")
        public char getQuoteChar();

        @Config("quote_policy")
        @ConfigDefault("\"MINIMAL\"")
        public QuotePolicy getQuotePolicy();

        @Config("escape")
        @ConfigDefault("null")
        public Optional<Character> getEscapeChar();

        @Config("null_string")
        @ConfigDefault("\"\"")
        public String getNullString();

        @Config("newline_in_field")
        @ConfigDefault("\"LF\"")
        public Newline getNewlineInField();
    }

    @Override
    public void transaction(ConfigSource config, Schema schema,
            FormatterPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);
        control.run(task.dump());
    }

    private Map<Integer, TimestampFormatter> newTimestampFormatters(
            TimestampFormatter.FormatterTask task, Schema schema)
    {
        ImmutableMap.Builder<Integer, TimestampFormatter> builder = new ImmutableBiMap.Builder<>();
        for (Column column : schema.getColumns()) {
            if (column.getType() instanceof TimestampType) {
                TimestampType tt = (TimestampType) column.getType();
                builder.put(column.getIndex(), new TimestampFormatter(tt.getFormat(), task));
            }
        }
        return builder.build();
    }

    @Override
    public PageOutput open(TaskSource taskSource, final Schema schema,
            FileOutput output)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);
        final LineEncoder encoder = new LineEncoder(output, task);
        final Map<Integer, TimestampFormatter> timestampFormatters =
                newTimestampFormatters(task, schema);
        final char delimiter = task.getDelimiterChar();
        final QuotePolicy quotePolicy = task.getQuotePolicy();
        final char quote = task.getQuoteChar() != '\0' ? task.getQuoteChar() : '"';
        final char escape = task.getEscapeChar().or(quotePolicy == QuotePolicy.NONE ? '\\' : '\"');
        final String newlineInField = task.getNewlineInField().getString();
        final String nullString = task.getNullString();

        // create a file
        encoder.nextFile();

        // write header
        if (task.getHeaderLine()) {
            writeHeader(schema, encoder, delimiter, quotePolicy, quote, escape, newlineInField, nullString);
        }

        return new PageOutput() {
            private final PageReader pageReader = new PageReader(schema);
            private final String delimiterString = String.valueOf(delimiter);

            public void add(Page page)
            {
                pageReader.setPage(page);
                while (pageReader.nextRecord()) {
                    schema.visitColumns(new ColumnVisitor() {
                        public void booleanColumn(Column column)
                        {
                            addDelimiter(column);
                            if (!pageReader.isNull(column)) {
                                addValue(Boolean.toString(pageReader.getBoolean(column)));
                            } else {
                                addNullString();
                            }
                        }

                        public void longColumn(Column column)
                        {
                            addDelimiter(column);
                            if (!pageReader.isNull(column)) {
                                addValue(Long.toString(pageReader.getLong(column)));
                            } else {
                                addNullString();
                            }
                        }

                        public void doubleColumn(Column column)
                        {
                            addDelimiter(column);
                            if (!pageReader.isNull(column)) {
                                addValue(Double.toString(pageReader.getDouble(column)));
                            } else {
                                addNullString();
                            }
                        }

                        public void stringColumn(Column column)
                        {
                            addDelimiter(column);
                            if (!pageReader.isNull(column)) {
                                addValue(pageReader.getString(column));
                            } else {
                                addNullString();
                            }
                        }

                        public void timestampColumn(Column column)
                        {
                            addDelimiter(column);
                            if (!pageReader.isNull(column)) {
                                Timestamp value = pageReader.getTimestamp(column);
                                addValue(timestampFormatters.get(column.getIndex()).format(value));
                            } else {
                                addNullString();
                            }
                        }

                        private void addDelimiter(Column column)
                        {
                            if (column.getIndex() != 0) {
                                encoder.addText(delimiterString);
                            }
                        }

                        private void addValue(String v)
                        {
                            encoder.addText(setEscapeAndQuoteValue(v, delimiter, quotePolicy, quote, escape, newlineInField, nullString));
                        }

                        private void addNullString()
                        {
                            encoder.addText(nullString);
                        }
                    });
                    encoder.addNewLine();
                }
            }

            public void finish()
            {
                encoder.finish();
            }

            public void close()
            {
                encoder.close();
            }
        };
    }

    private void writeHeader(Schema schema, LineEncoder encoder, char delimiter, QuotePolicy policy, char quote, char escape, String newline, String nullString)
    {
        String delimiterString = String.valueOf(delimiter);
        for (Column column : schema.getColumns()) {
            if (column.getIndex() != 0) {
                encoder.addText(delimiterString);
            }
            encoder.addText(setEscapeAndQuoteValue(column.getName(), delimiter, policy, quote, escape, newline, nullString));
        }
        encoder.addNewLine();
    }

    private String setEscapeAndQuoteValue(String v, char delimiter, QuotePolicy policy, char quote, char escape, String newline, String nullString)
    {
        StringBuilder escapedValue = new StringBuilder();
        char previousChar = ' ';

        boolean isRequireQuote = (policy == QuotePolicy.ALL || policy == QuotePolicy.MINIMAL && v.equals(nullString)) ? true : false;

        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);

            if (c == quote) {
                escapedValue.append(escape);
                escapedValue.append(c);
                isRequireQuote = true;
            } else if (c == '\r') {
                if (policy == QuotePolicy.NONE) {
                    escapedValue.append(escape);
                }
                escapedValue.append(newline);
                isRequireQuote = true;
            } else if (c == '\n') {
                if (previousChar != '\r') {
                    if (policy == QuotePolicy.NONE) {
                        escapedValue.append(escape);
                    }
                    escapedValue.append(newline);
                    isRequireQuote = true;
                }
            } else if (c == delimiter) {
                if (policy == QuotePolicy.NONE) {
                    escapedValue.append(escape);
                }
                escapedValue.append(c);
                isRequireQuote = true;
            } else {
                escapedValue.append(c);
            }
            previousChar = c;
        }

        if (policy != QuotePolicy.NONE && isRequireQuote) {
            return setQuoteValue(escapedValue.toString(), quote);
        } else {
            return escapedValue.toString();
        }
    }

    private String setQuoteValue(String v, char quote)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(quote);
        sb.append(v);
        sb.append(quote);

        return sb.toString();
    }
}
