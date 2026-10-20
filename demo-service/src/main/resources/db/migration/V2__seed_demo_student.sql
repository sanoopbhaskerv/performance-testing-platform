INSERT INTO students (first_name, last_name, email, program)
SELECT 'Demo', 'Student', 'demo.student@example.test', 'Performance Engineering'
WHERE NOT EXISTS (
    SELECT 1
    FROM students
    WHERE email = 'demo.student@example.test'
);