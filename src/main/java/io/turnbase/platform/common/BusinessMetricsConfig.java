package io.turnbase.platform.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * FR-MET-003 (observability.md §2.1): the four business gauges, re-derived from the database on a
 * 30-second interval rather than incremented by the code paths that create and destroy rooms — a
 * polled gauge is self-correcting, an incremented counter drifts the moment a pod dies mid-update.
 * Under more than one replica these are per-instance and identical (every pod queries the same
 * database), so a Grafana query aggregates them with {@code max()}, never {@code sum()}.
 */
@Configuration
@EnableScheduling
class BusinessMetricsConfig {

  private final JdbcTemplate jdbcTemplate;
  private final AtomicLong roomsActive = new AtomicLong();
  private final AtomicLong gameSessionsActive = new AtomicLong();
  private final AtomicLong usersRegistered = new AtomicLong();
  private final AtomicLong friendRequestsPending = new AtomicLong();

  BusinessMetricsConfig(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    Gauge.builder("turnbase_rooms_active", roomsActive, AtomicLong::get).register(meterRegistry);
    Gauge.builder("turnbase_game_sessions_active", gameSessionsActive, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder("turnbase_users_registered", usersRegistered, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder("turnbase_friend_requests_pending", friendRequestsPending, AtomicLong::get)
        .register(meterRegistry);
  }

  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void refresh() {
    roomsActive.set(count("SELECT count(*) FROM room WHERE state <> 'CLOSED'"));
    gameSessionsActive.set(count("SELECT count(*) FROM room WHERE active_session_id IS NOT NULL"));
    usersRegistered.set(count("SELECT count(*) FROM app_user WHERE deleted_at IS NULL"));
    // friend_request has no status column (observability.md §2.1, IMP-GATE-20) — a row's mere
    // existence means pending; accepting or declining one deletes it.
    friendRequestsPending.set(count("SELECT count(*) FROM friend_request"));
  }

  private long count(String sql) {
    Long result = jdbcTemplate.queryForObject(sql, Long.class);
    return result == null ? 0L : result;
  }
}
