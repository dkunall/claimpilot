-- ClaimPilot core schema. Money is in minor units (paise). Times are timestamptz.
create extension if not exists btree_gist;

create table staff (
    subject     text primary key,
    role        text not null check (role in ('AGENT', 'ASSESSOR', 'MANAGER')),
    created_at  timestamptz not null default now()
);

-- Products are data. A version is immutable once written; a change is a new version.
create table product_version (
    product_id    text not null,
    version       int  not null check (version > 0),
    line          text not null check (line in ('HEALTH', 'MOTOR')),
    name          text not null,
    coverages     jsonb not null,
    riders        jsonb not null,
    rating        jsonb not null,
    claim_rules   jsonb not null,
    created_by    text not null,
    created_at    timestamptz not null default now(),
    primary key (product_id, version)
);

create function forbid_change() returns trigger language plpgsql as $$
begin
    raise exception '% rows are immutable', tg_table_name using errcode = 'P0001';
end $$;

create trigger product_version_immutable before update or delete on product_version
    for each row execute function forbid_change();

create table approval_limit (
    role              text not null check (role in ('ASSESSOR', 'MANAGER')),
    line              text not null check (line in ('HEALTH', 'MOTOR')),
    max_amount_minor  bigint not null check (max_amount_minor > 0),
    primary key (role, line)
);

create table quote (
    id               uuid primary key,
    product_id       text not null,
    product_version  int  not null,
    holder_subject   text not null,
    agent_subject    text,
    holder_name      text not null,
    holder_email     text not null,
    inputs           jsonb not null,
    premium          jsonb not null,
    total_minor      bigint not null,
    status           text not null check (status in ('QUOTED', 'BOUND')),
    policy_id        uuid,
    created_at       timestamptz not null,
    expires_at       timestamptz not null,
    foreign key (product_id, product_version) references product_version (product_id, version)
);

create table policy (
    id                uuid primary key,
    seq               bigserial unique,             -- range-partitioning key for renewal batches
    number            text not null unique,
    holder_subject    text not null,
    agent_subject     text,
    holder_name       text not null,
    holder_email      text not null,
    product_id        text not null,
    product_version   int  not null,
    status            text not null check (status in ('ACTIVE', 'GRACE', 'LAPSED', 'CANCELLED', 'EXPIRED')),
    first_start       timestamptz not null,
    term_start        timestamptz not null,
    term_end          timestamptz not null,
    grace_until       timestamptz,
    autopay_token     text not null,
    lock_version      bigint not null default 0,
    created_at        timestamptz not null,
    foreign key (product_id, product_version) references product_version (product_id, version)
);
create index policy_holder on policy (holder_subject);
create index policy_due on policy (status, term_end);

-- Bitemporal: valid = when the coverage applies, recorded = when we believed it. Rows are never updated
-- except to close the recorded range of a superseded row, in the same transaction that writes its successor.
create table policy_version (
    id                  bigserial primary key,
    policy_id           uuid not null references policy (id),
    valid               tstzrange not null check (not isempty(valid) and not upper_inf(valid)),
    recorded            tstzrange not null check (not isempty(recorded)),
    product_version     int not null,
    coverage            jsonb not null,
    annual_premium_minor bigint not null,
    reason              text not null,
    endorsement_id      uuid,
    -- Two rows we currently believe can't both cover the same instant of one policy.
    constraint policy_version_no_overlap exclude using gist (policy_id with =, valid with &&)
        where (upper_inf(recorded))
);
create index policy_version_valid on policy_version using gist (policy_id, valid, recorded);

create table endorsement (
    id                   uuid primary key,
    policy_id            uuid not null references policy (id),
    effective_from       timestamptz not null,
    change               jsonb not null,
    premium_delta_minor  bigint not null,
    recorded_at          timestamptz not null,
    created_by           text not null
);
create index endorsement_policy on endorsement (policy_id);

create sequence receipt_seq;

create table payment (
    id             uuid primary key,
    policy_id      uuid not null references policy (id),
    kind           text not null check (kind in ('PREMIUM', 'ENDORSEMENT', 'RENEWAL', 'REFUND')),
    period_start   timestamptz not null,
    amount_minor   bigint not null,
    status         text not null check (status in ('SUCCEEDED', 'DECLINED')),
    gateway_ref    text not null,
    gateway_key    text not null unique,                -- the key we sent the gateway; one row per key
    receipt_no     text unique,
    created_at     timestamptz not null
);
create index payment_policy on payment (policy_id);

-- One row per (scope, caller, key). IN_PROGRESS rows block a concurrent duplicate; DONE rows replay.
create table idempotency_record (
    scope            text not null,
    subject          text not null,
    key              text not null,
    request_hash     text not null,
    status           text not null check (status in ('IN_PROGRESS', 'DONE')),
    response_status  int,
    response_body    text,
    created_at       timestamptz not null,
    primary key (scope, subject, key)
);

create sequence claim_seq;

create table claim (
    id                   uuid primary key,
    number               text not null unique,
    policy_id            uuid not null references policy (id),
    coverage_code        text not null,
    coverage_version_id  bigint references policy_version (id),
    loss_date            timestamptz not null,
    reported_at          timestamptz not null,
    claimed_minor        bigint not null check (claimed_minor > 0),
    reserve_minor        bigint not null,
    assessed_minor       bigint,
    approved_minor       bigint,
    status               text not null,
    stp_eligible         boolean,
    triage_reasons       jsonb,
    fraud_score          double precision,
    fraud_reasons        jsonb,
    assessed_by          text,
    payout_token         text not null,
    description          text not null,
    documents_required   int not null,
    created_by           text not null,
    decided_at           timestamptz,
    settled_at           timestamptz,
    lock_version         bigint not null default 0
);
create index claim_policy on claim (policy_id, loss_date);
create index claim_status on claim (status);

-- Identifiers are stored only as keyed HMACs: equal inputs link, but the raw phone or account never lands here.
create table claim_party (
    claim_id         uuid not null references claim (id),
    party_type       text not null check (party_type in ('PHONE', 'BANK_ACCOUNT', 'DEVICE', 'GARAGE', 'HOSPITAL')),
    party_key_hash   text not null,
    primary key (claim_id, party_type, party_key_hash)
);
create index claim_party_hash on claim_party (party_type, party_key_hash);

create table claim_document (
    id            uuid primary key,
    claim_id      uuid not null references claim (id),
    object_key    text not null unique,
    file_name     text not null,
    content_type  text not null,
    size_bytes    bigint not null,
    status        text not null check (status in ('PENDING', 'UPLOADED')),
    created_at    timestamptz not null
);
create index claim_document_claim on claim_document (claim_id);

create table claim_transition (
    id          bigserial primary key,
    claim_id    uuid not null references claim (id),
    from_status text,
    to_status   text not null,
    actor       text not null,
    note        text,
    at          timestamptz not null
);
create index claim_transition_claim on claim_transition (claim_id);

create table claim_approval (
    claim_id       uuid not null references claim (id),
    approver       text not null,
    role           text not null,
    amount_minor   bigint not null,
    at             timestamptz not null,
    primary key (claim_id, approver)
);

create table settlement (
    claim_id      uuid primary key references claim (id),
    amount_minor  bigint not null,
    gateway_ref   text not null,
    settled_by    text not null,
    at            timestamptz not null
);

-- Renewal batch items are idempotent by (policy, period).
create table renewal (
    policy_id        uuid not null references policy (id),
    period_start     timestamptz not null,
    status           text not null check (status in ('RENEWED', 'PAYMENT_FAILED')),
    product_version  int not null,
    premium_minor    bigint not null,
    payment_id       uuid,
    job_execution_id bigint,
    created_at       timestamptz not null,
    primary key (policy_id, period_start)
);

create table renewal_reminder (
    policy_id    uuid not null references policy (id),
    term_end     timestamptz not null,
    sent_at      timestamptz not null,
    primary key (policy_id, term_end)
);

create table outbox (
    id            bigserial primary key,
    event_id      uuid not null unique,
    topic         text not null,
    msg_key       text not null,
    type          text not null,
    payload       jsonb not null,
    created_at    timestamptz not null,
    published_at  timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

create table processed_event (
    consumer   text not null,
    event_id   uuid not null,
    at         timestamptz not null,
    primary key (consumer, event_id)
);

-- Synthetic starting limits; a deployment would tune these. 50k / 5L INR for health, 1L / 10L for motor.
insert into approval_limit (role, line, max_amount_minor) values
    ('ASSESSOR', 'HEALTH', 5000000), ('MANAGER', 'HEALTH', 50000000),
    ('ASSESSOR', 'MOTOR', 10000000), ('MANAGER', 'MOTOR', 100000000);
