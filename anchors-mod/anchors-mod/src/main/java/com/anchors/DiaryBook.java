package com.anchors;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Старый дневник: обычная "подписанная книга" (читается как книга, всегда светится),
 * но с особой текстурой и пометкой "потрёпанная копия".
 */
public class DiaryBook {

    /** Сколько символов помещается на одну страницу книги (с запасом, чтобы текст не обрезался). */
    private static final int MAX_CHARS_PER_PAGE = 190;
    /** Пустая строка между абзацами занимает место примерно как столько символов. */
    private static final int PARAGRAPH_COST = 30;

    // ТЕКСТ ДНЕВНИКА. Каждая строка это отдельный абзац. Можно смело править.
    private static final String TEXT = """
            Запись в дневнике
            Шкатулка. Музыкальная. Играет, если рядом постоять. Мелодия странная — колыбельная, которую я никогда не слышал, но как будто знал всю жизнь. Пока она играет, зомби перестают скрестись. Стоят и смотрят. Но в темноте между деревьями кто-то подпевает. Я не вижу кто.
            Старик из деревни сказал мне одну вещь, прежде чем отвернуться и захлопнуть ставни. Он говорил шёпотом, будто боялся, что его услышат не только мы:
            «Давным-давно здесь жил Коллекционер. Он потерял всех, кого любил, и не смог этого принять. Он придумал обряд — запирать воспоминания людей в вещах, которые они любили больше всего. Думал, так сохранит их. Обряд сработал лишь наполовину. Вещи стали якорями. В них застряла чужая боль. А сам Коллекционер... исчез. Растворился в том, что создал.
            Якоря разбросаны по миру. В заброшенных домах, в подвалах, в сундуках среди руин. Они ждут. Они тянутся к тому, кто их найдёт. И делятся тем, что помнят.
            Шкатулка помнит колыбельную. Пока она играет — чудовища рядом успокаиваются. Но мелодию слышат и те, кого ты не видишь.
            Старый фонарь помнит, как кто-то искал дорогу домой. Он освещает скрытое — невидимые тропы, тайники, следы. Но в его свете тени движутся неправильно.
            Тряпичная кукла помнит ребёнка, которого берегли. Она примет смертельный удар вместо тебя. Но с каждым разом ей все больше тяжело»
            Не бери их все. Не пользуйся ими часто. Потому что каждый якорь питается тем, что ты забираешь у мира. И мир начинает угасать.
            """;

    public static ItemStack create() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", "Дневник");
        tag.putString("author", "Неизвестный");
        tag.putInt("generation", 3);        // 3 = "Потрёпанная" (подпись в описании книги)
        tag.putBoolean("resolved", true);
        tag.putInt("CustomModelData", 1);   // по этой метке книга получает нашу старую текстуру

        ListTag pages = new ListTag();
        for (String page : paginate(TEXT)) {
            pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(page))));
        }
        tag.put("pages", pages);
        return book;
    }

    /** Разбивает текст на страницы по словам, чтобы ничего не обрезалось. */
    private static List<String> paginate(String text) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int used = 0;

        for (String paragraph : text.strip().split("\n")) {
            if (paragraph.isBlank()) continue;
            boolean startOfBlock = true;

            for (String word : paragraph.trim().split("\\s+")) {
                if (used > 0 && used + word.length() + 1 > MAX_CHARS_PER_PAGE) {
                    pages.add(page.toString().stripTrailing());
                    page = new StringBuilder();
                    used = 0;
                    startOfBlock = true;
                }
                if (!startOfBlock) {
                    page.append(' ');
                    used++;
                }
                page.append(word);
                used += word.length();
                startOfBlock = false;
            }
            page.append("\n\n");
            used += PARAGRAPH_COST;
        }
        if (!page.toString().isBlank()) {
            pages.add(page.toString().stripTrailing());
        }
        return pages;
    }
}
