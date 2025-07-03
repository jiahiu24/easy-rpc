package edu.jh.up; // 包声明与文件路径匹配

import edu.jh.up.spi.LoggerService; // 导入 LoggerService，因为它在不同的包

public class Main {
    public static void main(String[] args) {
        LoggerService service = LoggerService.getService();

        service.info("Hello SPI");
        service.debug("Hello SPI");
    }
}