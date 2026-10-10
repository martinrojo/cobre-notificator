package com.cobre.manager.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.port.in.GetNotificationEventUseCase;
import com.cobre.manager.domain.port.in.ListNotificationEventsUseCase;
import com.cobre.manager.infrastructure.adapter.in.web.security.ClientContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationEventController.class)
@Import(ClientContext.class)
class NotificationEventControllerTest {

  private static final String CLIENT_HEADER = "X-Client-Id";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ListNotificationEventsUseCase listNotificationEvents;

  @MockitoBean private GetNotificationEventUseCase getNotificationEvent;

  @Test
  void returnsEventsInSnakeCase() throws Exception {
    when(listNotificationEvents.list(any()))
        .thenReturn(
            List.of(
                new NotificationEvent(
                    "EVT001",
                    "CLIENT001",
                    "credit_card_payment",
                    "Credit card payment received for $150.00",
                    DeliveryStatus.DELIVERED,
                    Instant.parse("2024-03-15T09:30:22Z"),
                    Instant.parse("2024-03-15T09:30:22Z"))));

    mockMvc
        .perform(get("/notification_events").header(CLIENT_HEADER, "CLIENT001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].event_id").value("EVT001"))
        .andExpect(jsonPath("$[0].event_type").value("credit_card_payment"))
        .andExpect(jsonPath("$[0].delivery_status").value("DELIVERED"))
        .andExpect(jsonPath("$[0].created_at").value("2024-03-15T09:30:22Z"))
        .andExpect(jsonPath("$[0].delivery_date").value("2024-03-15T09:30:22Z"))
        .andExpect(jsonPath("$[0].client_id").doesNotExist());
  }

  @Test
  void returnsEmptyList() throws Exception {
    when(listNotificationEvents.list(any())).thenReturn(List.of());

    mockMvc
        .perform(get("/notification_events").header(CLIENT_HEADER, "CLIENT999"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void passesHeaderClientIdAndFiltersToUseCase() throws Exception {
    when(listNotificationEvents.list(any())).thenReturn(List.of());

    mockMvc
        .perform(
            get("/notification_events")
                .header(CLIENT_HEADER, "CLIENT001")
                .param("from", "2024-03-15T10:00:00Z")
                .param("to", "2024-03-15T16:00:00Z")
                .param("delivery_status", "delivered"))
        .andExpect(status().isOk());

    verify(listNotificationEvents)
        .list(
            new NotificationEventFilter(
                "CLIENT001",
                Instant.parse("2024-03-15T10:00:00Z"),
                Instant.parse("2024-03-15T16:00:00Z"),
                DeliveryStatus.DELIVERED));
  }

  @Test
  void rejectsMissingClientWith401ProblemDetail() throws Exception {
    mockMvc
        .perform(get("/notification_events"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Client identification is missing"));
    verify(listNotificationEvents, never()).list(any());
  }

  @Test
  void rejectsBlankClientWith401() throws Exception {
    mockMvc
        .perform(get("/notification_events").header(CLIENT_HEADER, "   "))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returns401BeforeValidatingParams() throws Exception {
    mockMvc
        .perform(get("/notification_events").param("delivery_status", "COMPLETED"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returns401ForUnknownPathWithoutClient() throws Exception {
    mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsUnknownDeliveryStatus() throws Exception {
    mockMvc
        .perform(
            get("/notification_events")
                .header(CLIENT_HEADER, "CLIENT001")
                .param("delivery_status", "COMPLETED"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "'delivery_status' must be one of [PENDING, RETRYING, DELIVERED, FAILED,"
                        + " NOT_SUBSCRIBED]"));
  }

  @Test
  void rejectsMalformedDate() throws Exception {
    mockMvc
        .perform(
            get("/notification_events")
                .header(CLIENT_HEADER, "CLIENT001")
                .param("from", "15-03-2024"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "'from' must be an ISO-8601 date-time with offset, e.g. 2024-03-15T10:00:00Z"));
  }

  @Test
  void rejectsInvertedRange() throws Exception {
    mockMvc
        .perform(
            get("/notification_events")
                .header(CLIENT_HEADER, "CLIENT001")
                .param("from", "2024-03-16T00:00:00Z")
                .param("to", "2024-03-15T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("'from' must be before or equal to 'to'"));
  }

  @Test
  void getsEventByIdForHeaderClient() throws Exception {
    when(getNotificationEvent.get("CLIENT002", "EVT003"))
        .thenReturn(
            new NotificationEvent(
                "EVT003",
                "CLIENT002",
                "credit_transfer",
                "Bank transfer received from Account #4567 for $1,500.00",
                DeliveryStatus.FAILED,
                Instant.parse("2024-03-15T11:20:18Z"),
                Instant.parse("2024-03-15T11:20:18Z")));

    mockMvc
        .perform(get("/notification_events/EVT003").header(CLIENT_HEADER, "CLIENT002"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.event_id").value("EVT003"))
        .andExpect(jsonPath("$.delivery_status").value("FAILED"))
        .andExpect(jsonPath("$.created_at").value("2024-03-15T11:20:18Z"))
        .andExpect(jsonPath("$.client_id").doesNotExist());
    verify(getNotificationEvent).get("CLIENT002", "EVT003");
  }

  @Test
  void returns404ProblemDetailWhenNotFound() throws Exception {
    when(getNotificationEvent.get("CLIENT001", "EVT003"))
        .thenThrow(NotificationEventNotFoundException.of("EVT003"));

    mockMvc
        .perform(get("/notification_events/EVT003").header(CLIENT_HEADER, "CLIENT001"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Notification event not found"));
  }

  @Test
  void getByIdRequiresClient() throws Exception {
    mockMvc.perform(get("/notification_events/EVT001")).andExpect(status().isUnauthorized());
    verify(getNotificationEvent, never()).get(any(), any());
  }
}
