package com.example.redlane;

public class DefinitelyRed {
    private String redLaneField = "red lane";

    public static void main(String[] args) {
        // INTENTIONAL RED-LANE FIXTURE: redLaneField must stay non-final.
        new DefinitelyRed().print();
    }

    private void print() {
        System.out.println(redLaneField);
    }
}
