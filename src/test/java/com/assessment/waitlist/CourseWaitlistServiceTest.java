package com.assessment.waitlist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CourseWaitlistServiceTest {

    @Test
    void enroll_assignsSeatUpToCapacity_thenWaitlistsInFifoOrder() {
        CourseWaitlistService service = new CourseWaitlistService((learnerId, courseId) -> true);
        service.registerCourse("CS-101", 2);

        assertEquals(OperationStatus.ENROLLED, service.enroll("CS-101", "L1").status());
        assertEquals(OperationStatus.ENROLLED, service.enroll("CS-101", "L2").status());
        assertEquals(OperationStatus.WAITLISTED, service.enroll("CS-101", "L3").status());
        assertEquals(OperationStatus.WAITLISTED, service.enroll("CS-101", "L4").status());

        CourseOffering course = service.getCourse("CS-101");
        assertEquals(Set.of("L1", "L2"), course.getEnrolledSnapshot());
        assertEquals(List.of("L3", "L4"), course.getWaitlistSnapshot());
    }

    @Test
    void enroll_rejectsIneligiblePrerequisites_withoutWaitlisting() {
        CourseWaitlistService service = new CourseWaitlistService(
                (learnerId, courseId) -> !learnerId.equals("L-bad"));
        service.registerCourse("CS-101", 1);

        OperationResult result = service.enroll("CS-101", "L-bad");
        assertEquals(OperationStatus.INELIGIBLE_PREREQUISITE, result.status());

        CourseOffering course = service.getCourse("CS-101");
        assertTrue(course.getEnrolledSnapshot().isEmpty());
        assertTrue(course.getWaitlistSnapshot().isEmpty());
    }

    @Test
    void enroll_preventsDuplicates_andPreservesWaitlistPosition() {
        CourseWaitlistService service = new CourseWaitlistService((learnerId, courseId) -> true);
        service.registerCourse("CS-101", 1);

        service.enroll("CS-101", "L1");
        service.enroll("CS-101", "L2");
        service.enroll("CS-101", "L3");

        assertEquals(OperationStatus.ALREADY_ENROLLED, service.enroll("CS-101", "L1").status());
        assertEquals(OperationStatus.ALREADY_WAITLISTED, service.enroll("CS-101", "L2").status());

        // L2 must still be ahead of L3 in FIFO order
        assertEquals(List.of("L2", "L3"), service.getCourse("CS-101").getWaitlistSnapshot());
    }

    @Test
    void drop_fromWaitlist_removesCleanlyWithoutAffectingEnrolled() {
        CourseWaitlistService service = new CourseWaitlistService((learnerId, courseId) -> true);
        service.registerCourse("CS-101", 1);

        service.enroll("CS-101", "L1");
        service.enroll("CS-101", "L2");
        service.enroll("CS-101", "L3");

        OperationResult dropResult = service.drop("CS-101", "L2");
        assertEquals(OperationStatus.DROPPED_FROM_WAITLIST, dropResult.status());
        assertTrue(dropResult.promotedLearnerId().isEmpty());

        assertEquals(Set.of("L1"), service.getCourse("CS-101").getEnrolledSnapshot());
        assertEquals(List.of("L3"), service.getCourse("CS-101").getWaitlistSnapshot());
    }

    @Test
    void drop_fromEnrolled_skipsAndRemovesIneligibleCandidates_andPromotesNextEligible() {
        Map<String, Boolean> eligibility = new HashMap<>();
        eligibility.put("L1", true);
        eligibility.put("L2", true);
        eligibility.put("L3", true);
        eligibility.put("L4", true);

        CourseWaitlistService service = new CourseWaitlistService(
                (learnerId, courseId) -> eligibility.getOrDefault(learnerId, false));
        service.registerCourse("CS-101", 1);

        service.enroll("CS-101", "L1"); // Enrolled
        service.enroll("CS-101", "L2"); // Waitlist #1
        service.enroll("CS-101", "L3"); // Waitlist #2
        service.enroll("CS-101", "L4"); // Waitlist #3

        // L2 and L3 lose eligibility while waiting
        eligibility.put("L2", false);
        eligibility.put("L3", false);

        OperationResult result = service.drop("CS-101", "L1");
        assertEquals(OperationStatus.DROPPED_FROM_ENROLLED, result.status());
        assertEquals("L4", result.promotedLearnerId().orElseThrow());
        assertEquals(List.of("L2", "L3"), result.skippedIneligibleLearners());

        CourseOffering course = service.getCourse("CS-101");
        assertEquals(Set.of("L4"), course.getEnrolledSnapshot());
        assertTrue(course.getWaitlistSnapshot().isEmpty());
    }

    @Test
    void operations_onUnknownCourse_returnCourseNotFound() {
        CourseWaitlistService service = new CourseWaitlistService((learnerId, courseId) -> true);

        assertEquals(OperationStatus.COURSE_NOT_FOUND, service.enroll("UNKNOWN", "L1").status());
        assertEquals(OperationStatus.COURSE_NOT_FOUND, service.drop("UNKNOWN", "L1").status());
    }

    @Test
    void drop_unknownLearnerOrEmptyWaitlist_handlesCleanly() {
        CourseWaitlistService service = new CourseWaitlistService((learnerId, courseId) -> true);
        service.registerCourse("CS-101", 2);

        assertEquals(OperationStatus.NOT_ENROLLED_OR_WAITLISTED, service.drop("CS-101", "L-ghost").status());

        service.enroll("CS-101", "L1");
        OperationResult dropResult = service.drop("CS-101", "L1");
        assertEquals(OperationStatus.DROPPED_FROM_ENROLLED, dropResult.status());
        assertTrue(dropResult.promotedLearnerId().isEmpty());
        assertTrue(dropResult.skippedIneligibleLearners().isEmpty());
    }

    @Test
    void concurrentEnrollAndDrop_preservesAllCourseInvariants() throws Exception {
        int capacity = 10;
        int totalLearners = 100;
        Set<String> ineligible = Set.of("L5", "L15", "L25", "L35", "L45");

        CourseWaitlistService service = new CourseWaitlistService(
                (learnerId, courseId) -> !ineligible.contains(learnerId));
        service.registerCourse("CS-999", capacity);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        Set<String> droppedLearners = ConcurrentHashMap.newKeySet();

        // 100 concurrent enrollments + 25 concurrent drops
        for (int i = 0; i < totalLearners; i++) {
            final String learnerId = "L" + i;
            futures.add(pool.submit(() -> {
                await(startGate);
                service.enroll("CS-999", learnerId);
            }));

            if (i % 4 == 0) {
                futures.add(pool.submit(() -> {
                    await(startGate);
                    OperationResult dropRes = service.drop("CS-999", learnerId);
                    if (dropRes.status() == OperationStatus.DROPPED_FROM_ENROLLED
                            || dropRes.status() == OperationStatus.DROPPED_FROM_WAITLIST) {
                        droppedLearners.add(learnerId);
                    }
                }));
            }
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        CourseOffering course = service.getCourse("CS-999");
        Set<String> enrolled = course.getEnrolledSnapshot();
        List<String> waitlist = course.getWaitlistSnapshot();

        // Invariant 1: 0 <= enrolled <= capacity
        assertTrue(enrolled.size() <= capacity, "Enrolled exceeded capacity");

        // Invariant 2: enrolled ∩ waitlist = ∅
        Set<String> intersection = new HashSet<>(enrolled);
        intersection.retainAll(waitlist);
        assertTrue(intersection.isEmpty(), "Overlap found between enrolled and waitlist: " + intersection);

        // Invariant 3: No duplicate entries in waitlist
        assertEquals(waitlist.size(), new HashSet<>(waitlist).size(), "Duplicate entries in waitlist");

        // Invariant 4: No ineligible learner enrolled or waitlisted
        for (String bad : ineligible) {
            assertFalse(enrolled.contains(bad));
            assertFalse(waitlist.contains(bad));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}