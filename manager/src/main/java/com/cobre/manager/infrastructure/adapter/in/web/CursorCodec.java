package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.model.PageCursor;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opaque cursor token: base64url of the JSON of the {@link PageCursor}. Not signed: the query is
 * always scoped by the authenticated client, so a tampered token only moves within the client's own
 * data or gets rejected.
 */
@Component
public class CursorCodec {

  private final JsonMapper jsonMapper;

  public CursorCodec(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public String encode(PageCursor cursor) {
    if (cursor == null) {
      return null;
    }
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(jsonMapper.writeValueAsBytes(cursor));
  }

  /** Null for no cursor; any token that does not decode to a valid cursor is a 400. */
  public PageCursor decode(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      return jsonMapper.readValue(Base64.getUrlDecoder().decode(token.strip()), PageCursor.class);
    } catch (RuntimeException e) {
      // Base64, JSON, missing fields or invalid values: Jackson 3 exceptions are unchecked.
      throw InvalidParameterException.invalidCursor();
    }
  }
}
