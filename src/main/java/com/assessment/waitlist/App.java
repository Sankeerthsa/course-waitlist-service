package com.assessment.waitlist;

import java.util.HashMap;
import java.util.Map;

public class App {

    public static void main(String[] args) {
        Map<String, Boolean> eligibilityMap = new HashMap<>();
        eligibilityMap.put("learner-1", true);
        eligibilityMap.put("learner-2", true);
        eligibilityMap.put("learner-3", true);
        eligibilityMap.put("learner-4", true);
        eligibilityMap.put("learner-ineligible", false);

        PrerequisiteChecker checker = (learnerId, courseId) ->
                eligibilityMap.getOrDefault(learnerId, false);

        CourseWaitlistService service = new CourseWaitlistService(checker);
        String courseId = "CS-401";
        service.registerCourse(courseId, 2);

        System.out.println("=== 1. Initial Enrollments (Capacity = 2) ===");
        printResult("Enroll learner-1", service.enroll(courseId, "learner-1"));
        printResult("Enroll learner-2", service.enroll(courseId, "learner-2"));

        System.out.println("\n=== 2. Prerequisite & Duplicate Checks ===");
        printResult("Enroll ineligible learner", service.enroll(courseId, "learner-ineligible"));
        printResult("Duplicate enroll learner-1", service.enroll(courseId, "learner-1"));

        System.out.println("\n=== 3. Waitlisting (FIFO Order) ===");
        printResult("Enroll learner-3 (Waitlist #1)", service.enroll(courseId, "learner-3"));
        printResult("Enroll learner-4 (Waitlist #2)", service.enroll(courseId, "learner-4"));
        printResult("Duplicate waitlist learner-3", service.enroll(courseId, "learner-3"));

        System.out.println("\n=== 4. Skip-and-Remove Promotion on Drop ===");
        eligibilityMap.put("learner-3", false);
        System.out.println("[Event] learner-3 became ineligible while waiting.");
        printResult("Drop learner-1", service.drop(courseId, "learner-1"));
    }

    private static void printResult(String action, OperationResult result) {
        System.out.printf("%-34s -> %s%n", action, result);
    }
}