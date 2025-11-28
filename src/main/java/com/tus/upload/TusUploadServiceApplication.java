package com.tus.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@ComponentScan(basePackages = {
        "com.tus.upload",
        "com.tus.upload.common", // scan shared components from tus-common
})
public class TusUploadServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TusUploadServiceApplication.class, args);
    }
}