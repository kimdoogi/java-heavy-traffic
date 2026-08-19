-- 선착순 쿠폰 스키마 (도메인 구현은 3주차, 스키마는 미리 고정)
CREATE TABLE coupon (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    total_quantity     INT          NOT NULL,
    remaining_quantity INT          NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,   -- 낙관적 락(E6 db-optimistic)
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_remaining_nonneg CHECK (remaining_quantity >= 0)
);

CREATE TABLE coupon_issue (
    id         BIGSERIAL PRIMARY KEY,
    coupon_id  BIGINT      NOT NULL REFERENCES coupon (id),
    user_id    BIGINT      NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_user UNIQUE (coupon_id, user_id)  -- 1인 1매 (E7 멱등성)
);
CREATE INDEX idx_coupon_issue_user ON coupon_issue (user_id);
