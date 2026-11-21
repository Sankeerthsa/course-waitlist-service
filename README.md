# Course Waitlist Service

A thread-safe, in-memory Java 17 service implementing course enrollment, duplicate prevention, prerequisite verification, and FIFO waitlist promotion.

## Prerequisites
* **Java:** JDK 17 or higher
* **Build Tool:** Apache Maven 3.8+

## Project Structure
```text
├── pom.xml
├── DESIGN.md
├── AI_LOG.md
├── README.md
└── src
    ├── main/java/com/assessment/waitlist/
    │   ├── App.java                      # CLI demonstration runner
    │   ├── CourseOffering.java           # Thread-safe per-course state container
    │   ├── CourseWaitlistService.java    # Core enrollment, drop, and promotion service
    │   ├── OperationResult.java          # Structured outcome & audit payload
    │   ├── OperationStatus.java          # Outcome status enum
    │   └── PrerequisiteChecker.java      # Functional interface for eligibility checks
    └── test/java/com/assessment/waitlist/
        └── CourseWaitlistServiceTest.java # Unit & multi-threaded concurrency tests