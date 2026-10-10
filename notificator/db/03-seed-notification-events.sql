-- Sample data from notification_events.json (completed -> DELIVERED, failed -> FAILED).
INSERT INTO notification_event
    (event_id, client_id, event_type, content, delivery_status, attempts, created_at, last_attempt_at)
VALUES
    ('EVT001', 'CLIENT001', 'credit_card_payment', 'Credit card payment received for $150.00', 'DELIVERED', 1, '2024-03-15 09:30:22', '2024-03-15 09:30:22'),
    ('EVT002', 'CLIENT001', 'debit_card_withdrawal', 'ATM withdrawal of $200.00', 'DELIVERED', 1, '2024-03-15 10:15:45', '2024-03-15 10:15:45'),
    ('EVT003', 'CLIENT002', 'credit_transfer', 'Bank transfer received from Account #4567 for $1,500.00', 'FAILED', 4, '2024-03-15 11:20:18', '2024-03-15 11:20:18'),
    ('EVT004', 'CLIENT002', 'debit_automatic_payment', 'Monthly utility bill payment of $85.50', 'DELIVERED', 1, '2024-03-15 12:05:33', '2024-03-15 12:05:33'),
    ('EVT005', 'CLIENT003', 'credit_refund', 'Refund processed for order #789 for $45.99', 'FAILED', 4, '2024-03-15 13:45:10', '2024-03-15 13:45:10'),
    ('EVT006', 'CLIENT003', 'debit_transfer', 'Money transfer sent to Account #8901 for $500.00', 'DELIVERED', 1, '2024-03-15 14:30:55', '2024-03-15 14:30:55'),
    ('EVT007', 'CLIENT001', 'credit_deposit', 'Direct deposit received from Employer XYZ for $2,500.00', 'DELIVERED', 1, '2024-03-15 15:20:40', '2024-03-15 15:20:40'),
    ('EVT008', 'CLIENT002', 'debit_purchase', 'Point of sale purchase at Store ABC for $75.25', 'DELIVERED', 1, '2024-03-15 16:10:15', '2024-03-15 16:10:15'),
    ('EVT009', 'CLIENT003', 'credit_cashback', 'Cashback reward credited for $25.00', 'FAILED', 4, '2024-03-15 17:25:30', '2024-03-15 17:25:30'),
    ('EVT010', 'CLIENT001', 'debit_subscription', 'Monthly streaming service payment of $14.99', 'DELIVERED', 1, '2024-03-15 18:05:12', '2024-03-15 18:05:12');
