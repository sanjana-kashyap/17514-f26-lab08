# PLAN.md

One section per milestone. Fill them in order, with the plan before you run anything, the guardrail
before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

### Task 1: Core modification, history, promotion (core-domain)

**Delivers:** `BookingService.modifyBooking(bookingId, requesterId, newInterval, newAttendees)`. It
applies owner/upcoming/not-cancelled checks (new `BookingRules.checkModifiable`, modelled on
`checkCancellable`), then a no-op short-circuit, then `rules.checkRequest` (the whole clause-3 list,
no booking cap), then room and own-calendar conflict checks through `ConflictChecker` with the
booking's own id filtered out of the lists. It saves the booking with a new `history` list
(`BookingChange`: what changed and when; `Booking.cancelled()` keeps it), publishes a new
`BookingModifiedEvent`, and only then calls the existing private `promoteWaitlist`, and only when
the interval changed. A no-op passes the owner/upcoming checks, then returns success with no write,
event, history entry, notification or promotion. **Modules:** `core-domain` only. **Depends on:**
nothing.

**Checkpoint:** `mvn -q -pl core-domain -am test` green. New `BookingServiceModifyTest` (a new file;
no shipped test edited) passes these cases:

- A 15-minute nudge succeeds.
- A move into a taken slot is `ROOM_UNAVAILABLE`, and the stored booking, history, waitlist and
  recorded events are all identical before and after.
- An overlap in another room is `USER_DOUBLE_BOOKED`.
- A user at the booking cap can still modify.
- Each clause-3 rule rejects.
- Non-owner, started and cancelled bookings are rejected.
- An attendees-only change publishes one event and promotes nobody.
- A shorten promotes the head waiter.
- A lengthen runs the pass and promotes nobody.
- A waiter whose own calendar clashes is skipped.
- A no-op emits nothing.
- A throwing publisher does not fail the modification.

**Could still go wrong with a green gate:** the self-exclusion is applied to the room check but not
the user-calendar check (or promotion runs before the modified booking is saved, letting a waiter
take its new slot). Only the cross-room overlap test and a promote-after-save ordering test catch
it.

### Task 2: Event log contract and utilization (storage + analytics, together per rule 6)

**Delivers:** the `booking-modified` wire event and its fold. `EventLogFormat` writes and lists it
(core fields carry the new state); `analytics/events.py` adds it to `KNOWN_EVENT_TYPES`, and
`build_ledger` updates the record's start/end/attendees. `utilization.py`, `revenue.py` and
`windows.py` are not edited, so both reports read the same fold. **Modules:** `storage`,
`analytics`. **Depends on:** Task 1.

**Checkpoint:** `mvn -q -pl storage -am test` and `cd analytics && pytest` both green. New tests and
a new fixture sit beside the shipped ones. A created line plus a modified line (120 minutes
shortened to 60) gives a utilization row with 60 booked minutes and `booking_count == 1`. A booking
moved to another day appears only in the new day's window. A line produced by `EventLogFormat`
parses in Python.

**Could still go wrong with a green gate:** analytics counts the booking twice or keeps the original
length because the Python fixture was hand-written and does not match what Java emits. The
round-trip test and the `booking_count == 1` assertion guard it.

### Task 3: Owner notification (notifications)

**Delivers:** `NotificationKind.BOOKING_MODIFIED`, a new template registered in
`TemplateRegistry.standard()`, and a `NotificationService.valuesFor` case. The message states the
old and new interval and attendee counts. The event carries the previous values in memory only;
`EventLogFormat` does not write them. **Modules:** `notifications`. **Depends on:** Task 1.

**Checkpoint:** `mvn -q -pl notifications -am test` green. A new test publishes a modified event and
asserts the owner's outbox body contains both the old and the new time text and attendee counts,
with `skippedCount() == 0`. This is the evidence the change request names.

**Could still go wrong with a green gate:** the template renders only new values, and a test that
checks only `queuedCount() == 1` passes. The test must assert on body text.

### Task 4: HTTP surface and documentation (http-api + docs)

**Delivers:** `PATCH /bookings/{bookingId}` (owner-only; `start` with `end` or `minutes`, optional
`attendees`; omitted fields keep their current values). It adds `Router.patch` and
`BookingsHandler.modify`, returns the updated booking plus any promotions, and adds history to
`JsonViews.booking`, so `GET /bookings/{id}` shows it to the owner or an administrator with no new
endpoint. Documentation: the `AGENTS.md` rule 6 event list, the `ARCHITECTURE.md` event list
("currently three" becomes four), and the `ARCHITECTURE.md` HTTP table row. **Modules:** `http-api`
plus the two doc files. **Depends on:** Tasks 1-3.

**Checkpoint:** `mvn -q -pl http-api -am test` green, then `mvn -B clean test` from the root and
`pytest` in `analytics/`. A new test class (no edit to `EndpointTest`) shows:

- Shorten, lengthen and move each show new times on a fresh `GET`, with a history entry.
- A non-owner PATCH returns 403, and an administrator's `GET` shows the history.
- A taken slot returns 409 and the booking is unchanged.
- A waiter is promoted after a shorten.
- A no-op PATCH returns 200 with empty history.

**Could still go wrong with a green gate:** history appears in the PATCH response but not in a later
`GET` by another user, or the docs are edited but the "three" count is left stale. Test PATCH
followed by a fresh `GET` as another user, and grep the docs for the old count.

### AGENTS.md rules 3 and 4

Task 1's checkpoint catches both. The cross-room and nudge cases only pass through `ConflictChecker`
(rule 3). A grep that finds `WaitlistStore.save/remove` only in `BookingService` among production
sources, plus the FIFO and ineligible-head promotion tests, catches a rule 4 break. Task 4's
`mvn -B clean test` repeats both on a cold tree.

### Out of scope, with reasons

- `billing`: the change request says the price does not change, with no re-price or re-invoice.
- `cli`: the change request says "Nothing in the CLI". It must still compile, which the cold build
  checks.
- `accounts`: the change request says modification uses the existing token and ownership checks.
- `analytics/revenue.py`, and any filtering or forking for modified bookings: the change request
  says the revenue report is out of scope and the ledger learns the event once.
- Room changes: the change request says that is a separate, unapproved request.
- `storage` stores, `StoreRegistry` and `InMemoryWaitlistStore`: history rides on `Booking`, and
  only `EventLogFormat` changes (rule 6). Waitlist writes stay in `BookingService` (rule 4).
- `TimeInterval` and `ConflictChecker` logic: rule 3 allows one overlap predicate.
- Shipped tests, module `pom.xml` edges and any JSON library: rules 7, 1 and 6.

---

## Milestone 2: The guardrail

Pick one recipe from `guardrails/README.md`. Configure it, then prove it binds.

**The recipe.** Which one you picked, the exact paths or trees it covers, and one line on what it
cannot catch.

**The captured block.** Paste the excerpt of the agent's own turn showing what you asked it to do,
its tool call, and the block the guardrail returned. A bare terminal paste with no agent turn around
it does not show the boundary binding.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through its gate. Any task
whose dependencies are done qualifies, including the smallest one.

**The takeover point.** Write this before you start the run. A concrete, conditional statement of
when you would stop the agent and write the code yourself. "If something looks wrong" does not
count. Name the trigger and name what you would do.

**The evidence.** Name the task from Milestone 1. Paste the result line of its gate command (run it
without `-q` so the line is in the output) and the result line of one `mvn -B clean test` run after
the task was finished. The module gate cannot see a cross-module compile break on a warm tree. A
cold build can, and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's declared scope
(plain `git diff` leaves out new files, but `git add -N . && git diff --stat` includes them). Note
anything it touched that you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the brief). Then the one
finding you settled, and how (the new failing test with its red run, or the evidence that dismissed
it). If nothing it reported held up, say how you checked its strongest claim.
