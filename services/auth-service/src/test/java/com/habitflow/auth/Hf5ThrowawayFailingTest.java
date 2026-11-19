package com.habitflow.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

// HF-5 THROWAWAY: proves a failing test turns Services CI red. Reverted before the PR is ready.
class Hf5ThrowawayFailingTest {

    @Test
    void deliberatelyFails() {
        fail("HF-5 throwaway: deliberate test failure");
    }
}
