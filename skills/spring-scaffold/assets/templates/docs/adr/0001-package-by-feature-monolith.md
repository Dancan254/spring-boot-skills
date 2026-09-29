# ADR-0001 — Package-by-feature monolith over microservices

- Status: Accepted
- Date: <today>

## Context
Small, early-stage project owned by one team. Cohesion and speed of iteration matter more than
independent deployability right now — there's no operational need to split services yet.

## Decision
Ship a single Spring Boot deployable, packaged by feature (one package per domain entity, each with
its own `controller → service → repository`) plus a `shared` package for the exception hierarchy and
config. Keep feature boundaries clean so any one can be extracted into its own service later.

## Consequences
- + Simple to run, test, and reason about; no distributed-systems tax up front.
- + Clean seams make a future service extraction mechanical rather than surgical.
- − Boundary discipline is manual today (nothing enforces it). Revisit with Spring Modulith if the
    app grows enough to need enforced module boundaries.
