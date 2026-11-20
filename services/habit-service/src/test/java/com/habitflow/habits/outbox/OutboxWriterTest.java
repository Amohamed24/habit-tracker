package com.habitflow.habits.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitflow.habits.entity.OutboxEvent;
import com.habitflow.habits.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OutboxWriter with a mocked repository and Boot's real ObjectMapper (via @JsonTest). Constructed
 * directly, so there is no transactional proxy: the MANDATORY check is covered by ToggleOutboxIT.
 */
@JsonTest
class OutboxWriterTest {

    @Autowired
    ObjectMapper objectMapper;

    private OutboxEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void serializationFailure_throwsIllegalStateException_andSavesNothing() throws JsonProcessingException {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });
        OutboxWriter writer = new OutboxWriter(failing, repository);

        assertThatThrownBy(() -> writer.write("habit", "9", "habit.created", Map.of("habitId", 9)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("habit.created")
                .hasCauseInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void eventId_uniquePerWrite() throws IOException {
        OutboxWriter writer = new OutboxWriter(objectMapper, repository);

        writer.write("habit", "9", "habit.created", Map.of("habitId", 9));
        writer.write("habit", "9", "habit.created", Map.of("habitId", 9));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        List<OutboxEvent> saved = captor.getAllValues();
        assertThat(saved.get(0).getId()).isNotNull().isNotEqualTo(saved.get(1).getId());
        // Row id is the envelope's event_id, per row.
        for (OutboxEvent event : saved) {
            assertThat(objectMapper.readTree(event.getPayload()).get("event_id").asText())
                    .isEqualTo(event.getId().toString());
        }
    }
}
