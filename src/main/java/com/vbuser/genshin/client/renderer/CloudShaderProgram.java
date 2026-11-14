package com.vbuser.genshin.client.renderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal GLSL program wrapper: compiles one vertex + one fragment shader from
 * the mod's assets and caches uniform locations.
 */
public final class CloudShaderProgram {

    private final String name;
    private final int program;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private CloudShaderProgram(String name, int program) {
        this.name = name;
        this.program = program;
    }

    public static CloudShaderProgram create(String name, String vertexAsset, String fragmentAsset)
            throws IOException {
        int vertex = compile(GL20.GL_VERTEX_SHADER, load(vertexAsset), name + ".vert");
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, load(fragmentAsset), name + ".frag");
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 8192);
            GL20.glDeleteProgram(program);
            throw new IOException("Cloud shader link failed (" + name + "):\n" + log);
        }
        return new CloudShaderProgram(name, program);
    }

    private static int compile(int type, String source, String label) throws IOException {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IOException("Cloud shader compile failed (" + label + "):\n" + numbered(log, source));
        }
        return shader;
    }

    /** Attaches the offending source lines to the driver log, which has no source otherwise. */
    private static String numbered(String log, String source) {
        StringBuilder builder = new StringBuilder(log);
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            builder.append('\n').append(i + 1).append(": ").append(lines[i]);
        }
        return builder.toString();
    }

    public static String load(String asset) throws IOException {
        String path = asset.startsWith("/") ? asset : "/" + asset;
        try (InputStream stream = CloudShaderProgram.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing shader resource " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public String getName() {
        return this.name;
    }

    public void use() {
        GL20.glUseProgram(this.program);
    }

    public static void stop() {
        GL20.glUseProgram(0);
    }

    public void dispose() {
        GL20.glDeleteProgram(this.program);
        this.uniforms.clear();
    }

    private int location(String uniform) {
        Integer cached = this.uniforms.get(uniform);
        if (cached != null) {
            return cached.intValue();
        }
        int location = GL20.glGetUniformLocation(this.program, uniform);
        this.uniforms.put(uniform, Integer.valueOf(location));
        return location;
    }

    public void set1i(String uniform, int value) {
        int location = location(uniform);
        if (location >= 0) {
            GL20.glUniform1i(location, value);
        }
    }

    public void set1f(String uniform, float value) {
        int location = location(uniform);
        if (location >= 0) {
            GL20.glUniform1f(location, value);
        }
    }

    public void set2f(String uniform, float x, float y) {
        int location = location(uniform);
        if (location >= 0) {
            GL20.glUniform2f(location, x, y);
        }
    }

    public void set3f(String uniform, double x, double y, double z) {
        int location = location(uniform);
        if (location >= 0) {
            GL20.glUniform3f(location, (float) x, (float) y, (float) z);
        }
    }
}
