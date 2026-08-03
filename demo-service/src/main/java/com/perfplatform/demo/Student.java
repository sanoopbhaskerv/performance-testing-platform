package com.perfplatform.demo;

public record Student(
        long id,
        String firstName,
        String lastName,
        String email,
        String program
) {
}