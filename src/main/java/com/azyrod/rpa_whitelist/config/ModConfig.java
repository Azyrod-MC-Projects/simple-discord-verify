package com.azyrod.rpa_whitelist.config;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ModConfig {
    public static final Path CONFIG_FILE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("rpa_whitelist.yaml").normalize();

    boolean incomplete = true;
    public ConfigValues values;
    boolean initialLoad = true;

    public boolean isIncomplete() {
        return incomplete;
    }

    private final static List<Class<?>> NON_PRIMITIVE_TYPES_TO_SKIP = Arrays.asList(
            String.class, MinecraftTextStyle.class, Long.class, Boolean.class
    );

    public void load() throws IOException {
        try (FileInputStream f = new FileInputStream(CONFIG_FILE_PATH.toString())) {
            loadFromStream(f);
        } catch (NoSuchFileException | FileNotFoundException e) {
            if (!initialLoad) {
                throw e;
            }
            initialLoad = false;
            createDefaultConfig();
            load();
        }
    }

    private void loadFromStream(InputStream input) throws IOException {
        ObjectMapper m = new ObjectMapper(new YAMLFactory());
        m.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        try {
            values = m.readValue(input, ConfigValues.class);
            ConfigValues defaultValues = m.readValue(defaultConfigStream(), ConfigValues.class);

            try {
                processFieldsAnnotations(values, defaultValues);
            } catch (RuntimeException e) {
                if (e.getCause() instanceof JsonMappingException cause) {
                    throw cause;
                }
                throw e;
            }

            ServerConfig serverConfig = values.server_config;
            if (!serverConfig.server_names.contains(serverConfig.server_name)) {
                throw new JsonMappingException(null, "The given server name '%s' is not in the server_names options".formatted(serverConfig.server_name));
            }
            incomplete = false;
        } catch (JsonMappingException e) {
            RPAWhitelist.LOGGER.error("Failed to load config: ", e);
            incomplete = true;
        }
    }

    private static InputStream defaultConfigStream() {
        InputStream stream = RPAWhitelist.class.getResourceAsStream("/assets/config/default_config.yaml");

        if (stream == null) {
            throw new IllegalStateException("This distribution of SimpleDiscordVerify is broken: cannot find the default configuration file inside of the mod's JAR.");
        }
        return stream;
    }

    public void createDefaultConfig() throws IOException {
        InputStream defaultConfigFile = defaultConfigStream();

        Files.createDirectories(CONFIG_FILE_PATH.getParent());
        Files.copy(defaultConfigFile, CONFIG_FILE_PATH);
    }

    private void processFieldsAnnotations(Object object, Object defaultObject) {
        Arrays.stream(object.getClass().getDeclaredFields()).forEach(field -> {
            JsonProperty annotation = field.getAnnotation(JsonProperty.class);

            if (annotation != null) {
                try {
                    field.setAccessible(true);
                    Object value = getFieldValue(field, object);
                    Object defaultValue = defaultObject != null ? getFieldValue(field, defaultObject) : null;
                    boolean isEmpty = isValueNullOrEmpty(value);
                    Class<?> type = field.getType();
                    String annotationDefaultValue = annotation.defaultValue();

                    if (isEmpty) {
                        if (Objects.equals(annotationDefaultValue, "true") && defaultValue != null) {
                            field.set(object, defaultValue);
                        } else if (List.of("true", "default").contains(annotationDefaultValue)) {
                            value = type.getDeclaredConstructor().newInstance();
                            field.set(object, value);
                            return;
                        } else if (annotation.required()) {
                            throw new JsonMappingException(null, "Required field '%s' cannot be empty".formatted(field.getName()));
                        }
                    }

                    value = getFieldValue(field, object);
                    if (value == null)
                        return;
                    if (type.equals(ArrayList.class)) {
                        ((ArrayList<?>) value).forEach(item -> {
                            processFieldsAnnotations(item, null);
                        });
                    } else if (!type.isPrimitive() && !NON_PRIMITIVE_TYPES_TO_SKIP.contains(type)) {
                        processFieldsAnnotations(value, defaultValue);
                    }
                } catch (IllegalAccessException | NoSuchMethodException | InstantiationException |
                         InvocationTargetException ignored) {
                    // TODO: Log ?
                } catch (JsonMappingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private Object getFieldValue(Field field, Object object) {
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            // TODO: Log ?
            return null;
        }
    }

    private boolean isValueNullOrEmpty(Object value) {
        if (value == null) {
            return true;
        }

        try {
            if ((Boolean) value.getClass().getMethod("isEmpty").invoke(value)) {
                return true;
            }
        } catch (NoSuchMethodException e) {
            // No isEmpty method -> pass
        } catch (IllegalAccessException | InvocationTargetException e) {
            // TODO: Log ?
        }
        return false;
    }

    public static class ConfigValues {
        @JsonProperty(required = true) public String discord_bot_token;
        @JsonProperty(required = true) public Long discord_server_id;
        @JsonProperty(required = true) public WhitelistConfig whitelist_config;
        @JsonProperty(required = true) public ServerConfig server_config;
        @JsonProperty(defaultValue = "sdv_") public String discord_commands_prefix;
        @JsonProperty(defaultValue = "true") public Boolean inactive_role_logic = false;
        @JsonProperty(defaultValue = "true") public Messages messages;
    }

    public static class Messages {
        @JsonProperty(defaultValue = "true") public MinecraftMessages minecraft;
        @JsonProperty(defaultValue = "true") public DiscordMessages discord;
    }

    public static class DiscordMessages {
        @JsonProperty(defaultValue = "true") public DiscordMessagesVerify verify;
        @JsonProperty(defaultValue = "true") public DiscordMessagesUnlink unlink;
    }

    public static class DiscordMessagesVerify {
        @JsonProperty(defaultValue = "true") public String success;
        @JsonProperty(defaultValue = "true") public String missing_role;
        @JsonProperty(defaultValue = "true") public String restarting;
        @JsonProperty(defaultValue = "true") public String login_missing;
        @JsonProperty(defaultValue = "true") public String invalid_login;
        @JsonProperty(defaultValue = "true") public String too_many_invalid_login;
    }

    public static class DiscordMessagesUnlink {
        @JsonProperty(defaultValue = "true") public String success;
        @JsonProperty(defaultValue = "true") public String not_linked;
    }

    public static class MinecraftMessages {
        @JsonProperty(defaultValue = "true") public ArrayList<MinecraftText> not_verified;
        @JsonProperty(defaultValue = "true") public ArrayList<MinecraftText> missing_role;
        @JsonProperty(defaultValue = "true") public ArrayList<MinecraftText> pending_verification;

        private Component not_verified_text;
        private Component missing_role_text;
        private Component pending_verification_text;

        public Component getNotVerifiedText() {
            if (not_verified_text == null) {
                not_verified_text = makeText(not_verified);
            }
            return not_verified_text;
        }

        public Component getMissingRoleText() {
            if (missing_role_text == null) {
                missing_role_text = makeText(missing_role);
            }
            return missing_role_text;
        }

        public Component getPendingVerificationText() {
            if (pending_verification_text == null) {
                pending_verification_text = makeText(pending_verification);
            }
            return pending_verification_text;
        }

        private Component makeText(ArrayList<MinecraftText> message) {
            MutableComponent text = Component.empty();

            message.forEach(mc_text -> {
                text.append(Component.literal(mc_text.text).setStyle(mc_text.style.getComputedStyle()));
            });

            return text;
        }
    }

    public static class MinecraftText {
        @JsonProperty(required = true) public String text;
        @JsonProperty(defaultValue = "default") public MinecraftTextStyle style;

        MinecraftText(String text, MinecraftTextStyle style) {
            this.text = text;
            this.style = style;
        }

        MinecraftText(String text) {
            this(text, null);
        }

        MinecraftText() {};
    }

    public static class MinecraftTextStyle {
        public TextColor color = null;
        public Boolean bold = null;
        public Boolean italic = null;
        public Boolean underlined = null;
        public Boolean strikethrough = null;

        private Style computedStyle = null;

        @JsonProperty("color")
        public void setColor(String color) {
            this.color = TextColor.parseColor(color).result().orElse(null);
        }

        public Style getComputedStyle() {
            if (computedStyle == null) {
                computedStyle = Style.EMPTY.withColor(color).withBold(bold).withItalic(italic)
                                     .withUnderlined(underlined).withStrikethrough(strikethrough);
            }
            return computedStyle;
        }

        MinecraftTextStyle(TextColor color) {
            this(color, null);
        }

        MinecraftTextStyle(TextColor color, Boolean bold) {
            this.color = color;
            this.bold = bold;
        }

        MinecraftTextStyle() {};
    }

    public static class ServerConfig {
        @JsonProperty(required = true) public ArrayList<String> server_names;
        @JsonProperty(required = true) public String server_name;
    }

    public static class WhitelistConfig {
        @JsonProperty(required = true) public ArrayList<Long> allowed_discord_roles;
        @JsonProperty(defaultValue = "default") public ArrayList<Long> disallowed_discord_roles = new ArrayList<>();
    }
}
