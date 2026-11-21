package com.assessment.waitlist;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages single-course enrollment, drops, and FIFO waitlist promotion
 * with prerequisite validation and per-course lock isolation.
 */
public class CourseWaitlistService {
    private final ConcurrentHashMap<String, CourseOffering> courses = new ConcurrentHashMap<>();
    private final PrerequisiteChecker prerequisiteChecker;

    public CourseWaitlistService(PrerequisiteChecker prerequisiteChecker) {
        this.prerequisiteChecker = Objects.requireNonNull(prerequisiteChecker, "prerequisiteChecker must not be null");
    }

    public void registerCourse(String courseId, int capacity) {
        CourseOffering offering = new CourseOffering(courseId, capacity);
        if (courses.putIfAbsent(courseId, offering) != null) {
            throw new IllegalArgumentException("Course already registered: " + courseId);
        }
    }

    public CourseOffering getCourse(String courseId) {
        validateCourseId(courseId);
        return courses.get(courseId);
    }

    public OperationResult enroll(String courseId, String learnerId) {
        validateIds(courseId, learnerId);
        CourseOffering course = courses.get(courseId);
        if (course == null) {
            return OperationResult.of(OperationStatus.COURSE_NOT_FOUND, learnerId);
        }

        ReentrantLock lock = course.getLock();
        lock.lock();
        try {
            if (course.getEnrolledLearnersInternal().contains(learnerId)) {
                return OperationResult.of(OperationStatus.ALREADY_ENROLLED, learnerId);
            }
            if (course.getWaitlistInternal().contains(learnerId)) {
                return OperationResult.of(OperationStatus.ALREADY_WAITLISTED, learnerId);
            }
            if (!prerequisiteChecker.hasMetPrerequisites(learnerId, courseId)) {
                return OperationResult.of(OperationStatus.INELIGIBLE_PREREQUISITE, learnerId);
            }

            if (course.getEnrolledLearnersInternal().size() < course.getCapacity()) {
                course.getEnrolledLearnersInternal().add(learnerId);
                return OperationResult.of(OperationStatus.ENROLLED, learnerId);
            }

            course.getWaitlistInternal().add(learnerId);
            return OperationResult.of(OperationStatus.WAITLISTED, learnerId);
        } finally {
            lock.unlock();
        }
    }

    public OperationResult drop(String courseId, String learnerId) {
        validateIds(courseId, learnerId);
        CourseOffering course = courses.get(courseId);
        if (course == null) {
            return OperationResult.of(OperationStatus.COURSE_NOT_FOUND, learnerId);
        }

        ReentrantLock lock = course.getLock();
        lock.lock();
        try {
            if (course.getWaitlistInternal().remove(learnerId)) {
                return OperationResult.of(OperationStatus.DROPPED_FROM_WAITLIST, learnerId);
            }

            if (!course.getEnrolledLearnersInternal().remove(learnerId)) {
                return OperationResult.of(OperationStatus.NOT_ENROLLED_OR_WAITLISTED, learnerId);
            }

            return promoteNextEligibleLearner(course, learnerId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evaluates waitlisted candidates in FIFO order under the course lock.
     * Any candidate who no longer meets prerequisites is removed and recorded ("Skip and Remove").
     */
    private OperationResult promoteNextEligibleLearner(CourseOffering course, String droppedLearnerId) {
        List<String> skipped = new ArrayList<>();
        String promotedLearner = null;

        Iterator<String> iterator = course.getWaitlistInternal().iterator();
        while (iterator.hasNext()) {
            String candidate = iterator.next();
            iterator.remove();

            if (prerequisiteChecker.hasMetPrerequisites(candidate, course.getCourseId())) {
                course.getEnrolledLearnersInternal().add(candidate);
                promotedLearner = candidate;
                break;
            }
            skipped.add(candidate);
        }

        return OperationResult.dropWithPromotion(
                OperationStatus.DROPPED_FROM_ENROLLED,
                droppedLearnerId,
                promotedLearner,
                skipped
        );
    }

    private void validateCourseId(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            throw new IllegalArgumentException("courseId must not be null or blank");
        }
    }

    private void validateIds(String courseId, String learnerId) {
        validateCourseId(courseId);
        if (learnerId == null || learnerId.isBlank()) {
            throw new IllegalArgumentException("learnerId must not be null or blank");
        }
    }
}