package org.sonar.server.ws;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;


public class NewFunctionTest {
    NewFunction newFunction = new NewFunction();
    @Test
    public void getTestString() {
        assertThat(newFunction.basicTest()).isEqualTo("Test");
    }
}
