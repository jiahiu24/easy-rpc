package edu.jh.up.spi;

public class SimpleLogger implements Logger {
    @Override
    public void info(String msg) {
        System.out.println("SimpleLogger INFO: " + msg);
    }

    @Override
    public void debug(String msg) {
        System.out.println("SimpleLogger DEBUG: " + msg);
    }
}