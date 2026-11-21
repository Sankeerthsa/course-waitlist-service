package com.assessment.waitlist;

@FunctionalInterface
public interface PrerequisiteChecker {
    boolean hasMetPrerequisites(String learnerId, String courseId);
}