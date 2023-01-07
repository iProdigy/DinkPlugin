package dinkplugin.message;

import com.google.gson.annotations.JsonAdapter;
import dinkplugin.util.ColorAdapter;
import dinkplugin.util.InstantAdapter;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

// A rich embed field used for notifications
// Notifications build one big embed with the fields of this class @@NotificationType
@Value
@Builder
public class Embed {
    public static final int MAX_DESCRIPTION_LENGTH = 4096;
    public static final int MAX_FOOTER_LENGTH = 2048;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/title.html
    // Filled in with the notification's title @@NotificationType.title
    String title;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/description.html
    // Includes the text of the notifier (e.g. "Forsen has levelled Attack to 100")
    String description;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/author.html
    Author author;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/color.html
    // Color trim of the rich embed
    @JsonAdapter(ColorAdapter.class)
    Color color;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/image.html
    // Contains the screenshot of the notification
    UrlEmbed image;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/thumbnail.html
    // Filled in with the notification's thumbnail (icon) @@NotificationType.thumbnail
    UrlEmbed thumbnail;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/fields.html
    // Contains extra notifier-specific fields.
    // For example, the loot notifier contains a field with the total value of the loot
    @Singular
    List<Field> fields;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/footer.html
    Footer footer;

    // https://birdie0.github.io/discord-webhooks-guide/structure/embed/timestamp.html
    @JsonAdapter(InstantAdapter.class)
    Instant timestamp;

    // Helper function to construct a simple embed that only contains an image
    public static Embed ofImage(String url) {
        return Embed.builder()
            .image(new UrlEmbed(url))
            .build();
    }

    @Value
    public static class UrlEmbed {
        String url;
    }
}
