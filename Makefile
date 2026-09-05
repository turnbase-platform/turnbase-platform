.PHONY: e2e

# FR-CICD-004/005: e2e is the one tier compose is allowed to serve (conventions.md §3).
# Runs post-merge only — never in the pre-merge pipeline, and it gates nothing (OQ-002).
e2e:
	./deploy/compose/e2e-smoke.sh
