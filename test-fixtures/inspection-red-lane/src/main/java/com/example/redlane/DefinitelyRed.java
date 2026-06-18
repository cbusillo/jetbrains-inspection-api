package com.example.redlane;

public class DefinitelyRed {
    private String unusedField = "unused";

    public static void main(String[] args) {
        // INTENTIONAL RED-LANE FIXTURE: MissingType must remain unresolved.
        MissingType value = MissingType.create();
        System.out.println(value);
    }

    private void unusedMethod() {
        System.out.println(unusedField);
    }
}
