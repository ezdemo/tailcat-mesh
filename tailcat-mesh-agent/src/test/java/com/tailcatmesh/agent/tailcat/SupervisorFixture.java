package com.tailcatmesh.agent.tailcat;

/** Small long-lived child used only to verify supervisor stream draining. */
public final class SupervisorFixture {

    private SupervisorFixture() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("ready");
        System.out.flush();
        String stderr = args.length > 0 && "diagnostics".equals(args[0])
                ? "stderr-line tcSensitiveConnBlob123456789"
                : "stderr-line";
        System.err.println(stderr);
        System.err.flush();
        Thread.sleep(300_000);
    }
}
