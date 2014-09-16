package org.quickload.spi;

import org.quickload.config.ConfigSource;

public interface ParserGuessPlugin
{
    public ParserGuessOperator openLineGuessOperator(ConfigSource config);

    public void shutdown();
}