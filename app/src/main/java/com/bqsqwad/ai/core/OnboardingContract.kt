package com.bqsqwad.ai.core

/** First-run onboarding survey contract (Prompt H). Shared with website. */
data class OnboardingProfile(
    val age: Int? = null,
    val dateOfBirth: String? = null,
    val purposes: List<String> = emptyList(),
    val role: String? = null,
    val roleOther: String? = null,
    val theme: String = "light",
    val completedAt: String? = null,
    val version: Int = ONBOARDING_VERSION,
)

const val ONBOARDING_VERSION = 1
const val DEFAULT_MIN_AGE = 13

val PURPOSE_OPTIONS = listOf(
    "companion" to "Companion",
    "coding" to "Coding",
    "chatting" to "Chatting",
    "projects" to "Projects",
    "team" to "Team",
    "live_streams" to "Live streams",
    "creativity" to "Creativity",
    "learning" to "Learning",
    "science" to "Science",
    "truth_guard" to "Truth Guard",
    "editing_generating" to "Editing / Generating",
)

val ROLE_OPTIONS = listOf(
    "student" to "Student",
    "founder" to "Founder",
    "co_founder" to "Co-founder",
    "editor" to "Editor",
    "scientist" to "Scientist",
    "developer" to "Developer",
    "designer" to "Designer",
    "writer" to "Writer / Author",
    "teacher" to "Teacher / Educator",
    "business_owner" to "Business owner",
    "marketer" to "Marketer",
    "content_creator" to "Content creator",
    "researcher" to "Researcher",
    "freelancer" to "Freelancer",
    "church_leader" to "Church / Organization leader",
    "job_seeker" to "Job seeker",
    "other" to "Other",
)

fun isUnderAge(age: Int, minAge: Int = DEFAULT_MIN_AGE): Boolean = age < minAge
fun isUnder18(age: Int): Boolean = age < 18
