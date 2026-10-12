package com.cobre.manager.infrastructure.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Identifies the client of every request except public paths. Reads X-Client-Id for now; to be
 * replaced by JWT validation.
 */
@Component
public class ClientIdentificationFilter extends OncePerRequestFilter {

  static final String CLIENT_ID_HEADER = "X-Client-Id";
  static final Set<String> PUBLIC_PATHS = Set.of();
  private static final String MDC_CLIENT_ID = "client_id";

  private final ClientContext clientContext;
  private final HandlerExceptionResolver exceptionResolver;

  public ClientIdentificationFilter(
      ClientContext clientContext,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
    this.clientContext = clientContext;
    this.exceptionResolver = exceptionResolver;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return PUBLIC_PATHS.contains(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String clientId = request.getHeader(CLIENT_ID_HEADER);
    if (clientId == null || clientId.isBlank()) {
      exceptionResolver.resolveException(
          request, response, null, UnauthenticatedClientException.missingClientId());
      return;
    }
    clientContext.clientId(clientId.strip());
    MDC.put(MDC_CLIENT_ID, clientContext.clientId());
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_CLIENT_ID);
    }
  }
}
