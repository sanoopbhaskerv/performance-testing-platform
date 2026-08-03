package com.perfplatform.demo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StudentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsRetrievesAndUpdatesAStudent() throws Exception {
        String studentJson = """
                {
                  "firstName": "Avery",
                  "lastName": "Chen",
                  "email": "avery.chen@example.test",
                  "program": "Computer Science"
                }
                """;

        String createdStudent = mockMvc.perform(post("/api/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studentJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.firstName", is("Avery")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number studentId = JsonPath.read(createdStudent, "$.id");
        String studentPath = "/api/students/" + studentId.longValue();

        mockMvc.perform(get(studentPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("avery.chen@example.test")))
                .andExpect(jsonPath("$.program", is("Computer Science")));

        mockMvc.perform(put(studentPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createdStudent.replace("Computer Science", "Data Science")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(studentId.intValue())))
                .andExpect(jsonPath("$.program", is("Data Science")));

        mockMvc.perform(get("/api/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void rejectsDuplicateEmailWithConflictResponse() throws Exception {
        String studentJson = """
                {
                  "firstName": "Casey",
                  "lastName": "Brown",
                  "email": "casey.brown@example.test",
                  "program": "Mathematics"
                }
                """;

        mockMvc.perform(post("/api/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studentJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studentJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }
}