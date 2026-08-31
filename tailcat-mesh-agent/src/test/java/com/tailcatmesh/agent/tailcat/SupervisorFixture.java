package com.tailcatmesh.agent.tailcat;

/** Small long-lived child used only to verify supervisor stream draining. */
public final class SupervisorFixture {

    private SupervisorFixture() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("ready");
        System.out.flush();
        System.err.println("stderr-line");
        System.err.flush();
        Thread.sleep(300_000);
    }
}
