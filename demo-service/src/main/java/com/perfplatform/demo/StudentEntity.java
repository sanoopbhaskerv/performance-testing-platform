package com.perfplatform.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "students")
public class StudentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String firstName;

    @Column(nullable = false, length = 80)
    private String lastName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 120)
    private String program;

    protected StudentEntity() {
    }

    private StudentEntity(StudentRequest request) {
        update(request);
    }

    public static StudentEntity from(StudentRequest request) {
        return new StudentEntity(request);
    }

    public void update(StudentRequest request) {
        firstName = request.firstName();
        lastName = request.lastName();
        email = request.email();
        program = request.program();
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getProgram() {
        return program;
    }
}