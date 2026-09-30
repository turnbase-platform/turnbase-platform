.PHONY: e2e reset-db seed

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

# FR-CICD-004/005: e2e is the one tier compose is allowed to serve (conventions.md §3).
# Runs post-merge only — never in the pre-merge pipeline, and it gates nothing (OQ-002).
e2e:
	./deploy/compose/e2e-smoke.sh

# local-environment.md §6: seeds are applied by an ApplicationRunner at startup under the `local`
# profile, so re-running them is restarting the monolith. Already-seeded games are skipped.
seed:
	$(COMPOSE) restart platform

# local-environment.md §6: drop schema, re-migrate, re-seed. Needs the compose stack's .env (or the
# same variables in the environment). The clients of the schema are stopped first, the schema is
# recreated as the owner with the grants and extensions 01-roles.sql gives a fresh volume, and
# Redis is flushed so no session or room outlives the users it named. Starting the monolith
# re-runs Flyway and the seeder, which re-uploads every bundle in turnbase.seed.bundles-dir.
reset-db:
	$(COMPOSE) stop frontend gamehost platform
	$(COMPOSE) up -d --wait postgres redis
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U turnbase_owner -d turnbase -c \
	  "DROP SCHEMA public CASCADE; CREATE SCHEMA public; \
	   GRANT USAGE, CREATE ON SCHEMA public TO turnbase_app; \
	   CREATE EXTENSION IF NOT EXISTS pgcrypto; \
	   CREATE EXTENSION IF NOT EXISTS pg_trgm; \
	   CREATE EXTENSION IF NOT EXISTS btree_gin;"
	$(COMPOSE) exec -T redis sh -c 'redis-cli -a "$$TURNBASE_REDIS_PASSWORD" FLUSHALL'
	$(COMPOSE) up -d --wait platform gamehost frontend
