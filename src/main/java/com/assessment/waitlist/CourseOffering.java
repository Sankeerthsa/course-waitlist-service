package com.assessment.waitlist;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe state container for a single course offering.
 * Guards enrolled and waitlist mutations using a fair per-course {@link ReentrantLock}.
 */
public class CourseOffering {
    private final String courseId;
    private final int capacity;
    private final Set<String> enrolledLearners;
    private final LinkedHashSet<String> waitlist;
    private final ReentrantLock lock;

    public CourseOffering(String courseId, int capacity) {
        if (courseId == null || courseId.isBlank()) {
            throw new IllegalArgumentException("courseId must not be null or blank");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.courseId = courseId;
        this.capacity = capacity;
        this.enrolledLearners = new HashSet<>(capacity);
        this.waitlist = new LinkedHashSet<>();
        this.lock = new ReentrantLock(true);
    }

    public String getCourseId() {
        return courseId;
    }

    public int getCapacity() {
        return capacity;
    }

    ReentrantLock getLock() {
        return lock;
    }

    Set<String> getEnrolledLearnersInternal() {
        return enrolledLearners;
    }

    LinkedHashSet<String> getWaitlistInternal() {
        return waitlist;
    }

    public Set<String> getEnrolledSnapshot() {
        lock.lock();
        try {
            return Collections.unmodifiableSet(new HashSet<>(enrolledLearners));
        } finally {
            lock.unlock();
        }
    }

    public List<String> getWaitlistSnapshot() {
        lock.lock();
        try {
            return List.copyOf(waitlist);
        } finally {
            lock.unlock();
        }
    }
}