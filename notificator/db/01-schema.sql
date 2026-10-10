CREATE TABLE subscription (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    client_id   VARCHAR(64)   NOT NULL,
    event_type  VARCHAR(64)   NOT NULL,
    webhook_url VARCHAR(2048) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_subscription_client_type UNIQUE (client_id, event_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE notification_event (
    event_id         VARCHAR(64)   NOT NULL,
    client_id        VARCHAR(64)   NOT NULL,
    event_type       VARCHAR(64)   NOT NULL,
    content          TEXT          NOT NULL,
    delivery_status  VARCHAR(20)   NOT NULL,
    attempts         INT           NOT NULL DEFAULT 0,
    created_at       DATETIME(3)   NOT NULL,
    last_attempt_at  DATETIME(3)   NULL,
    delivery_url     VARCHAR(2048) NULL,
    last_http_status SMALLINT      NULL,
    last_error       VARCHAR(500)  NULL,
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    CONSTRAINT ck_notification_event_status CHECK (
        delivery_status IN ('PENDING', 'RETRYING', 'DELIVERED', 'FAILED', 'NOT_SUBSCRIBED')
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- GET /notification_events sin filtro de estado
CREATE INDEX idx_event_client_created
    ON notification_event (client_id, created_at);

-- GET /notification_events?delivery_status=...
CREATE INDEX idx_event_client_status_created
    ON notification_event (client_id, delivery_status, created_at);