# AI-Assisted Engineering Log

This document records how AI tooling was utilized during the design, implementation, and verification of the Course Waitlist service, including critical review decisions and verification steps.

---

## 1. Architecture & Policy Design
* **Prompt Summary:** *"Design a Java 17 service for single-course enrollment and FIFO waitlist promotion with prerequisite checks, thread safety, and clear operation outcomes."*
* **AI Output:** Proposed a `CourseWaitlistService` backed by a `ConcurrentHashMap<String, CourseOffering>`, using `LinkedHashSet` for $O(1)$ FIFO waitlist operations and a structured `OperationResult` object.
* **Decision (Accept with Edits):**
  * **Accepted:** Using `LinkedHashSet` over `ArrayDeque` so duplicate checks (`ALREADY_WAITLISTED`) and mid-queue drops run in $O(1)$ time instead of $O(N)$.
  * **Edited:** Added explicit tracking for `skippedIneligibleLearners` inside `OperationResult` so callers have an audit trail when a waitlisted learner is evicted during promotion.

## 2. Concurrency & Locking Strategy
* **Prompt Summary:** *"Evaluate locking strategies for per-course enrollment and waitlist promotion under high concurrency in Java 17."*
* **AI Output:** Suggested either `synchronized` blocks on the `CourseOffering` instance or a `ReentrantLock` per course.
* **Decision (Accept with Edits):**
  * **Accepted:** Per-course locking to avoid global lock contention across distinct courses.
  * **Edited:** Selected `new ReentrantLock(true)` (fair lock) over intrinsic `synchronized` blocks to minimize thread starvation when multiple threads contend for the final seats of a single course simultaneously.

## 3. Edge Case & Promotion Loop Implementation
* **Prompt Summary:** *"Implement the drop and promotion logic where waitlisted learners are re-checked for prerequisites at promotion time."*
* **AI Output:** Generated a `while` loop polling the waitlist until either a valid candidate is promoted or the waitlist is exhausted.
* **Decision (Rejected / Refactored):**
  * **Issue Identified:** The initial AI draft invoked `prerequisiteChecker.hasMetPrerequisites(...)` *outside* the lock to avoid holding the lock during external calls, which introduced a check-then-act race condition where capacity or waitlist order could shift mid-promotion.
  * **Resolution:** Kept the check inside the per-course lock boundary to guarantee strict serializability and preserve the invariant `enrolled ∩ waitlist = ∅`, documenting external I/O latency as a known trade-off in `DESIGN.md`.

## 4. Test Suite & Concurrency Verification
* **Prompt Summary:** *"Write JUnit 5 tests covering edge cases and a multi-threaded stress test using ExecutorService and CountDownLatch."*
* **AI Output:** Generated unit tests for happy paths, duplicate checks, prerequisite rejections, and a 50-thread concurrent enrollment/drop test.
* **Decision (Accept with Edits):**
  * **Added:** Explicit post-execution invariant assertions verifying `enrolled.size() <= capacity`, zero intersection between `enrolled` and `waitlist`, and exact conservation of total learners across enrolled, waitlisted, rejected, and dropped states.

---

## 5. Verification Evidence
All functional and concurrency tests were executed locally via Maven (`mvn clean test`):
* **Unit Tests:** Verified `ENROLLED`, `WAITLISTED`, `ALREADY_ENROLLED`, `ALREADY_WAITLISTED`, `INELIGIBLE_PREREQUISITE`, `DROPPED_FROM_ENROLLED`, and `DROPPED_FROM_WAITLIST` transitions.
* **Skip-and-Remove Test:** Confirmed that when learner A (front of waitlist) loses prerequisite eligibility before a seat opens, dropping an enrolled learner skips and removes A and promotes learner B.
* **Concurrency Stress Test:** Executed 100 concurrent threads performing mixed `enroll` and `drop` operations against a course with capacity 10; all runs passed with zero invariant violations or deadlocks