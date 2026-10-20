package com.perfplatform.demo;

import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;

import java.util.List;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional
    public Student create(StudentRequest request) {
        if (studentRepository.existsByEmailIgnoreCase(request.email())) {
            throw StudentConflictException.emailAlreadyExists(request.email());
        }
        return toStudent(studentRepository.save(StudentEntity.from(request)));
    }

    @Transactional
    public List<Student> list() {
        return studentRepository.findAllByOrderByIdAsc()
                .stream()
                .map(this::toStudent)
                .toList();
    }

    @Transactional
    public Student get(long studentId) {
        return studentRepository.findById(studentId)
                .map(this::toStudent)
                .orElseThrow(() -> StudentNotFoundException.forId(studentId));
    }

    @Transactional
    public Student update(long studentId, StudentRequest request) {
        StudentEntity student = studentRepository.findById(studentId)
                .orElseThrow(() -> StudentNotFoundException.forId(studentId));

        if (studentRepository.existsByEmailIgnoreCaseAndIdNot(request.email(), studentId)) {
            throw StudentConflictException.emailAlreadyExists(request.email());
        }

        student.update(request);
        return toStudent(student);
    }

    private Student toStudent(StudentEntity student) {
        return new Student(
                student.getId(),
                student.getFirstName(),
                student.getLastName(),
                student.getEmail(),
                student.getProgram()
        );
    }
}