package com.cobre.manager.infrastructure.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

class ClientIdentificationFilterTest {

  private final ClientContext clientContext = new ClientContext();
  private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
  private final FilterChain chain = mock(FilterChain.class);
  private final ClientIdentificationFilter filter =
      new ClientIdentificationFilter(clientContext, resolver);
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void setsClientContextAndContinuesChain() throws Exception {
    var request = request("/notification_events");
    request.addHeader("X-Client-Id", "CLIENT001");
    FilterChain assertingChain =
        (req, res) -> {
          assertThat(MDC.get("client_id")).isEqualTo("CLIENT001");
          chain.doFilter(req, res);
        };

    filter.doFilter(request, response, assertingChain);

    assertThat(clientContext.clientId()).isEqualTo("CLIENT001");
    verify(chain).doFilter(request, response);
    verifyNoInteractions(resolver);
    assertThat(MDC.get("client_id")).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsMissingOrBlankHeader(String header) throws Exception {
    var request = request("/notification_events");
    if (header != null) {
      request.addHeader("X-Client-Id", header);
    }

    filter.doFilter(request, response, chain);

    verify(resolver)
        .resolveException(any(), any(), isNull(), any(UnauthenticatedClientException.class));
    verify(chain, never()).doFilter(any(), any());
    assertThat(clientContext.clientId()).isNull();
  }

  @Test
  void rejectsUnknownPathWithoutHeader() throws Exception {
    filter.doFilter(request("/does-not-exist"), response, chain);

    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void skipsPublicPaths() throws Exception {
    var request = request("/ping");

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(resolver);
  }

  private static MockHttpServletRequest request(String uri) {
    return new MockHttpServletRequest("GET", uri);
  }
}
