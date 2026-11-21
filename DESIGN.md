# System Design: Course Waitlist Service

## 1. Service Boundary & Scope
The `CourseWaitlistService` manages enrollment, dropping, and automated waitlist promotion for individual `CourseOffering` instances within an in-memory store. 
* **In Scope:** Capacity enforcement, prerequisite verification, duplicate request handling, deterministic FIFO waitlist ordering, dynamic eligibility re-evaluation during promotion, and thread-safe state transitions.
* **Out of Scope:** Persistent storage, authentication/authorization, course catalog management, and external event publishing (designed to be added cleanly as post-commit hooks if needed).

---

## 2. Domain Model & API Design

### Core Models
* **`CourseOffering`**: Encapsulates the mutable state of a single course (`courseId`, `capacity`, `enrolledLearners`, `waitlist`, and a per-course fair `ReentrantLock`).
* **`PrerequisiteChecker`**: A functional interface (`boolean hasMetPrerequisites(String learnerId, String courseId)`) decoupling enrollment logic from external academic record lookups.
* **`OperationStatus`**: Enum representing explicit, deterministic outcomes:
  * `ENROLLED`
  * `WAITLISTED`
  * `ALREADY_ENROLLED`
  * `ALREADY_WAITLISTED`
  * `INELIGIBLE_PREREQUISITE`
  * `DROPPED_FROM_ENROLLED`
  * `DROPPED_FROM_WAITLIST`
  * `NOT_ENROLLED_OR_WAITLISTED`
  * `COURSE_NOT_FOUND`
* **`OperationResult`**: Immutable record/value object returning the `OperationStatus`, the acting `learnerId`, the `promotedLearnerId` (if a drop triggered a promotion), and `skippedIneligibleLearners` (audit trail of learners removed from the waitlist due to lapsed prerequisites).

### Service API (`CourseWaitlistService`)
* `registerCourse(String courseId, int capacity)`: Initializes a thread-safe course offering.
* `enroll(String courseId, String learnerId)`: Validates prerequisites, checks duplicates, assigns an open seat, or appends to the FIFO waitlist.
* `drop(String courseId, String learnerId)`: Removes the learner from either the waitlist or active roster; if an enrolled seat is freed, triggers FIFO promotion.

---

## 3. Data Structures & Algorithmic Complexity
Inside each `CourseOffering`:
* **Enrolled Roster (`Set<String>` backed by `HashSet`):** Provides $O(1)$ lookup for duplicate enrollment checks and $O(1)$ removal on drop.
* **Waitlist (`Set<String>` backed by `LinkedHashSet`):** Combines a hash table with a doubly-linked list. This guarantees:
  * $O(1)$ duplicate waitlist checks (`contains`) without scanning the queue.
  * $O(1)$ insertion at the tail (`add`) and $O(1)$ removal from the head (`iterator().next()` / `remove`), strictly preserving FIFO order.
  * $O(1)$ arbitrary removal if a waitlisted learner drops out before reaching the front.

---

## 4. Invariants
Every public operation preserves the following invariants upon releasing the course lock:
1. **Capacity Bound:** $0 \le \vert{}\text{enrolled}\vert{} \le \text{capacity}$
2. **Disjoint Sets:** $\text{enrolled} \cap \text{waitlist} = \emptyset$ (a learner can never be both enrolled and waitlisted simultaneously).
3. **Eligibility Guarantee:** Every learner in `enrolled` passed `PrerequisiteChecker` at the exact moment of enrollment or promotion.
4. **FIFO Integrity:** Relative insertion order in `waitlist` is strictly preserved across concurrent enrollments, drops, and promotions.

---

## 5. Concurrency & Locking Strategy
* **Course-Level Striped Locking:** Rather than using a global service-wide lock (which creates contention across unrelated courses) or lock-free CAS loops (which complicate multi-step atomic promotions), each `CourseOffering` owns a dedicated `ReentrantLock(true)`.
* **Fairness (`fair = true`):** Ensures threads acquire the lock in approximate arrival order, preventing thread starvation under heavy concurrent burst traffic for popular courses.
* **Atomic Compound Transitions:** Checking capacity, verifying prerequisites, mutating `enrolled` and `waitlist`, and running the promotion loop occur inside a single `try / finally` lock block. No intermediate state is ever visible to other threads.

---

## 6. Key Policy Trade-offs

### Waitlist Eligibility Policy: "Skip and Remove" vs. "Skip and Retain"
When an enrolled learner drops and a seat opens, the service re-checks `PrerequisiteChecker` for candidates at the front of the waitlist.
* **Chosen Policy ("Skip and Remove"):** If the head candidate is no longer eligible (e.g., dropped a corequisite or failed a prerequisite course), they are removed from the waitlist, recorded in `OperationResult.skippedIneligibleLearners`, and the service immediately evaluates the next candidate in FIFO order.
* **Trade-off:** Prevents "head-of-line blocking" where an ineligible learner stalls seat allocation or sits indefinitely in a queue they can no longer clear. The downside is that a learner whose eligibility temporarily glitches loses their FIFO spot; however, returning `skippedIneligibleLearners` provides full observability for downstream notification systems.