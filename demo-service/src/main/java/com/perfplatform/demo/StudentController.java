package com.perfplatform.demo;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @PostMapping
    public ResponseEntity<Student> createStudent(@Valid @RequestBody StudentRequest request) {
        Student student = studentService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{studentId}")
                .buildAndExpand(student.id())
                .toUri();
        return ResponseEntity.created(location).body(student);
    }

    @GetMapping("/{studentId}")
    public Student getStudent(@PathVariable long studentId) {
        return studentService.get(studentId);
    }

    @GetMapping
    public List<Student> listStudents() {
        return studentService.list();
    }

    @PutMapping("/{studentId}")
    public Student updateStudent(@PathVariable long studentId, @Valid @RequestBody StudentRequest request) {
        return studentService.update(studentId, request);
    }
}