-- Local only: every simulated client (CLIENT001..CLIENT008) subscribed to every event type,
-- delivering to webhook-mock. http is accepted only because compose sets WEBHOOK_ALLOW_HTTP.
INSERT INTO subscription (client_id, event_type, webhook_url)
SELECT c.client_id, t.event_type, CONCAT('http://webhook-mock:8090/webhooks/', c.client_id)
FROM (SELECT CONCAT('CLIENT00', seq) AS client_id FROM seq_1_to_8) c
CROSS JOIN (
    SELECT 'credit_card_payment' AS event_type
    UNION ALL SELECT 'credit_cashback'
    UNION ALL SELECT 'credit_deposit'
    UNION ALL SELECT 'credit_refund'
    UNION ALL SELECT 'credit_transfer'
    UNION ALL SELECT 'debit_automatic_payment'
    UNION ALL SELECT 'debit_card_withdrawal'
    UNION ALL SELECT 'debit_purchase'
    UNION ALL SELECT 'debit_subscription'
    UNION ALL SELECT 'debit_transfer'
) t;
