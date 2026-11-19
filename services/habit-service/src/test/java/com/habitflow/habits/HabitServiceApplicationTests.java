package com.habitflow.habits;

import com.habitflow.habits.client.AuthServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

class HabitServiceApplicationTests extends IntegrationTestBase {

    @MockBean
    AuthServiceClient authServiceClient;

    @Test
    void contextLoads() {
    }
}
