package br.com.c6tag.diagnostico.monitor.storage

import android.database.sqlite.SQLiteDatabase

internal object MonitorSchema {
    fun create(database: SQLiteDatabase) {
        statements.forEach(database::execSQL)
        database.execSQL("INSERT INTO monitor_state(id) VALUES(1)")
        database.execSQL("INSERT INTO monitor_settings(id) VALUES(1)")
    }

    private val statements = listOf(
        """CREATE TABLE monitor_state(
            id INTEGER PRIMARY KEY CHECK(id=1),
            reference_id INTEGER,
            balance_cents INTEGER,
            revision INTEGER NOT NULL DEFAULT 0,
            revision_cause TEXT NOT NULL DEFAULT 'BOOTSTRAP',
            continuity INTEGER NOT NULL DEFAULT 0 CHECK(continuity IN(0,1)),
            process_session_id TEXT,
            reference_session_id TEXT,
            last_observation_id INTEGER NOT NULL DEFAULT 0,
            last_observed_at_ms INTEGER
        )""",
        """CREATE TABLE monitor_settings(
            id INTEGER PRIMARY KEY CHECK(id=1),
            limit_cents INTEGER NOT NULL DEFAULT 30000 CHECK(limit_cents>=0),
            repeat_minutes INTEGER NOT NULL DEFAULT 15 CHECK(repeat_minutes IN(15,30,60)),
            pause_minutes INTEGER NOT NULL DEFAULT 30 CHECK(pause_minutes IN(15,30,60,120)),
            theme TEXT NOT NULL DEFAULT 'SYSTEM' CHECK(theme IN('SYSTEM','LIGHT','DARK'))
        )""",
        """CREATE TABLE monitor_references(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            balance_cents INTEGER NOT NULL,
            confirmed_at_ms INTEGER NOT NULL,
            observation_cursor INTEGER NOT NULL,
            session_opened_at_ms INTEGER NOT NULL,
            cursor_at_session_open INTEGER NOT NULL,
            session_id TEXT NOT NULL UNIQUE
        )""",
        """CREATE TABLE monitor_reference_sessions(
            id TEXT PRIMARY KEY NOT NULL,
            opened_at_ms INTEGER NOT NULL,
            observation_cursor INTEGER NOT NULL,
            base_reference_id INTEGER,
            status TEXT NOT NULL CHECK(status IN('ACTIVE','CONFIRMED','CANCELLED','EXPIRED'))
        )""",
        """CREATE UNIQUE INDEX monitor_one_active_reference_session
            ON monitor_reference_sessions((1)) WHERE status='ACTIVE'""",
        """CREATE TABLE monitor_reference_inclusions(
            reference_id INTEGER NOT NULL REFERENCES monitor_references(id),
            observation_id INTEGER NOT NULL,
            PRIMARY KEY(reference_id,observation_id)
        )""",
        """CREATE TABLE monitor_sources(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT,
            notification_key TEXT,
            posted_at_ms INTEGER,
            movement_kind TEXT CHECK(movement_kind IN('TAG_DEBIT','PIX_CREDIT')),
            amount_cents INTEGER CHECK(amount_cents>=0),
            first_observation_id INTEGER NOT NULL,
            last_observation_id INTEGER NOT NULL,
            first_observed_at_ms INTEGER NOT NULL,
            last_observed_at_ms INTEGER NOT NULL,
            first_event_type TEXT NOT NULL,
            conflicting INTEGER NOT NULL DEFAULT 0 CHECK(conflicting IN(0,1)),
            status TEXT NOT NULL DEFAULT 'UNTRACKED'
                CHECK(status IN('UNTRACKED','PROVISIONAL','APPLIED','COVERED','PENDING','IGNORED')),
            reference_id INTEGER REFERENCES monitor_references(id),
            reference_session_id TEXT REFERENCES monitor_reference_sessions(id)
        )""",
        """CREATE UNIQUE INDEX monitor_publication_identity
            ON monitor_sources(package_name,notification_key,posted_at_ms)
            WHERE package_name IS NOT NULL AND package_name!=''
                AND notification_key IS NOT NULL AND notification_key!='' AND posted_at_ms>0""",
        """CREATE INDEX monitor_source_key ON monitor_sources(package_name,notification_key)""",
        """CREATE TABLE monitor_source_evidence(
            source_id INTEGER NOT NULL REFERENCES monitor_sources(id),
            content_hash TEXT NOT NULL,
            movement_kind TEXT CHECK(movement_kind IN('TAG_DEBIT','PIX_CREDIT')),
            amount_cents INTEGER CHECK(amount_cents>=0),
            first_observation_id INTEGER NOT NULL,
            last_observation_id INTEGER NOT NULL,
            PRIMARY KEY(source_id,content_hash)
        )""",
        """CREATE INDEX monitor_evidence_hash ON monitor_source_evidence(content_hash,source_id)""",
        """CREATE TABLE monitor_entries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entry_kind TEXT NOT NULL CHECK(entry_kind IN('REFERENCE','TAG_DEBIT','PIX_CREDIT')),
            reference_id INTEGER NOT NULL REFERENCES monitor_references(id),
            source_id INTEGER UNIQUE REFERENCES monitor_sources(id),
            amount_cents INTEGER NOT NULL,
            balance_after_cents INTEGER NOT NULL,
            recorded_at_ms INTEGER NOT NULL,
            observation_id INTEGER,
            CHECK(entry_kind='REFERENCE' OR (source_id IS NOT NULL AND amount_cents>=0))
        )""",
        """CREATE INDEX monitor_entries_kind_page ON monitor_entries(entry_kind,id DESC)""",
        """CREATE TABLE monitor_pending(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_id INTEGER NOT NULL REFERENCES monitor_sources(id),
            observation_id INTEGER NOT NULL,
            deduplication_key TEXT NOT NULL UNIQUE,
            reason TEXT NOT NULL,
            reference_id INTEGER REFERENCES monitor_references(id),
            reference_session_id TEXT REFERENCES monitor_reference_sessions(id),
            movement_kind TEXT CHECK(movement_kind IN('TAG_DEBIT','PIX_CREDIT')),
            amount_cents INTEGER CHECK(amount_cents>=0),
            status TEXT NOT NULL DEFAULT 'OPEN'
                CHECK(status IN('OPEN','APPLIED','NOT_COUNTED','COVERED_BY_REFERENCE')),
            created_at_ms INTEGER NOT NULL,
            decided_at_ms INTEGER
        )""",
        """CREATE INDEX monitor_pending_page ON monitor_pending(status,id DESC)""",
        """CREATE TABLE monitor_alert_cycles(
            id TEXT PRIMARY KEY NOT NULL,
            status TEXT NOT NULL CHECK(status IN('ACTIVE','PAUSED','CLOSED')),
            opened_at_ms INTEGER NOT NULL,
            version INTEGER NOT NULL CHECK(version>0),
            last_submitted_at_ms INTEGER,
            next_reminder_at_ms INTEGER,
            pause_until_ms INTEGER,
            intent_version INTEGER,
            intent_requested_at_ms INTEGER,
            last_submission_outcome TEXT
                CHECK(last_submission_outcome IN('ACCEPTED','PERMISSION_DENIED','CHANNEL_BLOCKED','FAILED')),
            emission_count INTEGER NOT NULL DEFAULT 0 CHECK(emission_count>=0),
            critical_alerted INTEGER NOT NULL DEFAULT 0 CHECK(critical_alerted IN(0,1)),
            intent_critical INTEGER NOT NULL DEFAULT 0 CHECK(intent_critical IN(0,1)),
            CHECK((intent_version IS NULL)=(intent_requested_at_ms IS NULL))
        )""",
        """CREATE UNIQUE INDEX monitor_one_active_alert
            ON monitor_alert_cycles((1)) WHERE status!='CLOSED'""",
    )
}
