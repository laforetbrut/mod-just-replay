package com.vyrriox.justreplay.mcp;

import com.google.gson.JsonObject;

/**
 * A camera of the observation tools: where the picture is taken from (the eye, not the feet), where it looks and
 * how wide, in the game's conventions: yaw 0 looks south (+Z) and 90 west (-X), a positive pitch looks down, the
 * field of view is vertical, in degrees. It projects points of the world on a picture the way the game draws them:
 * view = roll about Z, then pitch about X, then yaw + 180 degrees about Y, and an OpenGL perspective.
 *
 * @param source where the camera comes from: {@code project}, {@code current}, {@code free}
 * @author vyrriox
 */
public record McpCamera(double x, double y, double z, double yaw, double pitch, double roll, double fov, String source) {
    /** Height of the eyes above the position a camera keyframe stores. */
    public static final double EYE_HEIGHT = 1.62;
    /** Nearest distance the game draws, in front of the eye. */
    public static final double NEAR = 0.05;
    public static final double MIN_FOV = 1.0;
    public static final double MAX_FOV = 170.0;

    public McpCamera {
        fov = Math.max(MIN_FOV, Math.min(MAX_FOV, Double.isFinite(fov) ? fov : 70.0));
        source = source == null ? "free" : source;
    }

    /** The same camera with another source label. */
    public McpCamera withSource(String source) {
        return new McpCamera(x, y, z, yaw, pitch, roll, fov, source);
    }

    /** Unit vector the camera looks along, as {@code Entity.getViewVector} gives it. */
    public double[] forward() {
        return basis()[2];
    }

    /**
     * The camera's right, up and forward unit vectors in world space (rows of the view rotation, so that
     * {@code view(v) = (right . v, up . v, -forward . v)}).
     */
    double[][] basis() {
        double cy = Math.cos(Math.toRadians(yaw));
        double sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch));
        double sp = Math.sin(Math.toRadians(pitch));
        double cr = Math.cos(Math.toRadians(roll));
        double sr = Math.sin(Math.toRadians(roll));
        // Ry(yaw + 180): rows of the world -> camera rotation before pitch and roll.
        double[] r0 = {-cy, 0.0, -sy};
        double[] r1 = {0.0, 1.0, 0.0};
        double[] r2 = {sy, 0.0, -cy};
        // Rx(pitch) applied after.
        double[] p0 = r0;
        double[] p1 = {cp * r1[0] - sp * r2[0], cp * r1[1] - sp * r2[1], cp * r1[2] - sp * r2[2]};
        double[] p2 = {sp * r1[0] + cp * r2[0], sp * r1[1] + cp * r2[1], sp * r1[2] + cp * r2[2]};
        // Rz(roll) last.
        double[] right = {cr * p0[0] - sr * p1[0], cr * p0[1] - sr * p1[1], cr * p0[2] - sr * p1[2]};
        double[] up = {sr * p0[0] + cr * p1[0], sr * p0[1] + cr * p1[1], sr * p0[2] + cr * p1[2]};
        // The camera looks down its -Z axis.
        double[] forward = {-p2[0], -p2[1], -p2[2]};
        return new double[][]{right, up, forward};
    }

    /**
     * Where a point of the world falls on a picture of {@code width x height} pixels taken by this camera.
     *
     * @param screenX pixels from the left edge (may be outside the picture)
     * @param screenY pixels from the top edge
     * @param depth   distance in front of the camera along its view direction; negative behind it
     * @param distance straight distance from the eye
     * @param inFront the point is in front of the near plane
     * @param onScreen in front and inside the picture
     */
    public record Projection(double screenX, double screenY, double depth, double distance, boolean inFront, boolean onScreen) {
    }

    public Projection project(double px, double py, double pz, int width, int height) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        double[][] b = basis();
        double cx = b[0][0] * dx + b[0][1] * dy + b[0][2] * dz;
        double cyy = b[1][0] * dx + b[1][1] * dy + b[1][2] * dz;
        double depth = b[2][0] * dx + b[2][1] * dy + b[2][2] * dz;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double tan = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) width / Math.max(1, height);
        boolean inFront = depth > NEAR;
        // Behind the camera the perspective divide flips the point; keep the direction readable instead.
        double d = Math.max(Math.abs(depth), 1.0E-6);
        double ndcX = cx / (d * tan * aspect);
        double ndcY = cyy / (d * tan);
        double sx = (ndcX + 1.0) / 2.0 * width;
        double sy = (1.0 - ndcY) / 2.0 * height;
        boolean onScreen = inFront && sx >= 0.0 && sx <= width && sy >= 0.0 && sy <= height;
        return new Projection(sx, sy, depth, distance, inFront, onScreen);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("source", source);
        o.addProperty("x", round(x, 3));
        o.addProperty("y", round(y, 3));
        o.addProperty("z", round(z, 3));
        o.addProperty("yaw", round(yaw, 2));
        o.addProperty("pitch", round(pitch, 2));
        o.addProperty("roll", round(roll, 2));
        o.addProperty("fov", round(fov, 2));
        return o;
    }

    static double round(double value, int digits) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double f = Math.pow(10, digits);
        return Math.round(value * f) / f;
    }
}
