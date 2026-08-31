package com.tailcatmesh.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Tailcat Mesh control-plane Server. */
@SpringBootApplication
public class TailcatMeshServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TailcatMeshServerApplication.class, args);
    }
}
