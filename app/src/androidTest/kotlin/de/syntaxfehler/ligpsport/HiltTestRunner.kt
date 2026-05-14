package de.syntaxfehler.ligpsport

import androidx.test.runner.AndroidJUnitRunner

/**
 * Default Android JUnit runner. (Named historically for the Hilt
 * variant we used while DI was bolted in; we no longer need a Hilt
 * test application but keep the testInstrumentationRunner name stable
 * so manifest references don't churn.)
 */
class HiltTestRunner : AndroidJUnitRunner()
