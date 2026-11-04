package ai.devvio.rd;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * In-app reference: server setup, touch/mouse semantics, keyboard, clipboard,
 * file transfer, troubleshooting and shortcuts.
 *
 * <p>Sections are filterable - a single search field narrows the list as you
 * type, which matters once there are more than a handful of topics. Command
 * snippets are tappable to copy them to the phone clipboard.
 */
public class HelpActivity extends Activity {

    private LinearLayout list;
    private final List<View> cards = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private TextView emptyNote;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, Ui.SP_4);

        // ---- header ----
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(pad, Ui.dp(this, Ui.SP_3), pad, Ui.dp(this, Ui.SP_2));
        TextView back = Ui.toolButton(this, "‹", 20);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        LinearLayout ht = Ui.column(this);
        ht.addView(Ui.text(this, "راهنما", Ui.TS_TITLE, Ui.TEXT, true));
        ht.addView(Ui.text(this, "هر چیزی که برای شروع لازم است", Ui.TS_MICRO, Ui.TEXT_FAINT, false));
        LinearLayout.LayoutParams hlp = Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        hlp.leftMargin = Ui.dp(this, Ui.SP_3);
        head.addView(ht, hlp);
        root.addView(head);

        // ---- search ----
        EditText search = Ui.input(this, "جستجو در راهنما…");
        search.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = pad;
        slp.rightMargin = pad;
        slp.bottomMargin = Ui.dp(this, Ui.SP_2);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s == null ? "" : s.toString());
            }
        });
        root.addView(search);

        // ---- list ----
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = Ui.column(this);
        int p = Ui.dp(this, Ui.SP_2);
        list.setPadding(pad, p, pad, Ui.dp(this, Ui.SP_7));
        sv.addView(list);
        root.addView(sv, Ui.lpm(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        emptyNote = Ui.text(this, "چیزی پیدا نشد — عبارت دیگری را امتحان کنید.",
                Ui.TS_CAPTION, Ui.TEXT_DIM, false);
        emptyNote.setGravity(Gravity.CENTER);
        emptyNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(this, Ui.SP_6);
        root.addView(emptyNote, elp);

        buildContent();
        return root;
    }

    private void buildContent() {
        addCard("۱. اجرای سرور",
                "روی کامپیوتر (لینوکس، مک یا ویندوز) فایل devviord را اجرا کنید. آدرسی که چاپ "
                        + "می‌شود را در صفحه اول اپ وارد کنید. پورت پیش‌فرض 5900 است.",
                "devviord --token 1234");
        addCard("۲. اتصال از گوشی",
                "گوشی و کامپیوتر باید روی یک شبکه باشند. در اپ، نشانی مانند 192.168.1.10:5900 را "
                        + "بنویسید و همان توکن را وارد کنید. اگر روی شبکه محلی هستید، فایروال "
                        + "کامپیوتر باید پورت 5900 را باز کند.");
        addCard("۳. حالت لمسی (Touch)",
                "لمس = کلیک در همان نقطه، کشیدن = درگ، دو انگشت = اسکرول، دو ضربه = دابل‌کلیک. "
                        + "برای کار با منوها و فهرست‌ها طبیعی‌ترین حالت است.");
        addCard("۴. حالت موس (Mouse)",
                "صفحه مثل تاچ‌پد عمل می‌کند: کشیدن انگشت نشانگر را حرکت می‌دهد، یک ضربه کلیک چپ "
                        + "و دو ضربه دابل‌کلیک است. حساسیت را از تنظیمات تنظیم کنید.");
        addCard("۵. کیبورد و تایپ فارسی",
                "دکمه ⌨ صفحه‌کلید اندروید را باز می‌کند. هر متنی که تایپ کنید - فارسی، عربی، چینی "
                        + "یا ایموجی - به صورت UTF-8 به سرور می‌رسد. کلیدهای Backspace، Enter، Tab و "
                        + "کلیدهای جهت هم پشتیبانی می‌شوند؛ کیبورد بلوتوث نیز کار می‌کند.");
        addCard("۶. کلیپ‌بورد",
                "⎘ متن گوشی را به سرور می‌فرستد و ⤓ متن سرور را روی گوشی کپی می‌کند. اگر "
                        + "همگام‌سازی روشن باشد، متن کپی‌شده روی سرور خودکار روی گوشی هم می‌آید.");
        addCard("۷. انتقال فایل",
                "⇄ صفحه فایل‌ها را باز می‌کند: آپلود از گوشی به سرور، دانلود به پوشه "
                        + "Downloads/DevvioRD، ساخت پوشه، تغییر نام و حذف. آپلود تکه‌تکه است و بعد "
                        + "از قطعی شبکه از همان‌جا ادامه می‌یابد.");
        addCard("۸. کیفیت و مصرف داده",
                "در تنظیمات چهار پیش‌تنظیم (حداکثر، متعادل، سبک، کم‌مصرف) هست. برای شبکه ضعیف "
                        + "«کم‌مصرف» را انتخاب کنید: تصویر کوچک‌تر و نرخ فریم کمتر می‌شود.");
        addCard("۹. رفع اشکال",
                "• «اتصال برقرار نشد»: IP و پورت را بررسی کنید و مطمئن شوید سرور در حال اجراست.\n"
                        + "• «توکن نامعتبر»: توکن اپ باید دقیقاً با ‎--token‎ سرور یکی باشد.\n"
                        + "• تصویر کند است: کیفیت را کم کنید یا مقیاس را پایین بیاورید.\n"
                        + "• کلیپ‌بورد کار نمی‌کند: دسترسی کلیپ‌بورد را در اندروید ۱۰+ تأیید کنید.\n"
                        + "• فایل آپلود نمی‌شود: پوشه سرور باید قابل نوشتن باشد.");
        addCard("English quick start",
                "1) Run devviord --token 1234 on the host.\n"
                        + "2) Enter the printed address in the app.\n"
                        + "3) TOUCH for direct taps, MOUSE for trackpad-style control.\n"
                        + "4) ⌨ keyboard · ⎘/⤓ clipboard · ⇄ file transfer.",
                "devviord --token 1234 --fps 30 --quality 80");
    }

    private void addCard(String title, String body) {
        addCard(title, body, null);
    }

    private void addCard(String title, String body, String command) {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.cardBg(this));
        int p = Ui.dp(this, Ui.SP_4);
        c.setPadding(p, Ui.dp(this, Ui.SP_3), p, p);
        c.addView(Ui.text(this, title, Ui.TS_LABEL + 2, Ui.ACCENT, true));
        TextView t = Ui.text(this, body, Ui.TS_CAPTION + 1, Ui.TEXT, false);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.addGap(c, t, this, Ui.SP_2);
        if (command != null) {
            c.addView(commandBox(command));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, Ui.SP_2);
        list.addView(c, lp);
        cards.add(c);
        keys.add((title + " " + body + " " + (command == null ? "" : command)).toLowerCase());
    }

    /** Terminal-style snippet; tapping copies it. */
    private View commandBox(final String command) {
        LinearLayout box = Ui.row(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.roundRect(Ui.BG, Ui.R_SM, Ui.LINE, 1, this));
        int p = Ui.dp(this, Ui.SP_3);
        box.setPadding(p, Ui.dp(this, Ui.SP_2), p, Ui.dp(this, Ui.SP_2));
        TextView code = Ui.mono(this, command, Ui.TS_CAPTION, Ui.ACCENT);
        code.setTextIsSelectable(true);
        box.addView(code, Ui.lpm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView copy = Ui.text(this, "کپی", Ui.TS_MICRO, Ui.TEXT_FAINT, true);
        box.addView(copy);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("DevvioRD", command));
                    Toast.makeText(HelpActivity.this, "دستور کپی شد", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, Ui.SP_1);
        box.setLayoutParams(lp);
        return box;
    }

    private void filter(String q) {
        String needle = q.trim().toLowerCase();
        int shown = 0;
        for (int i = 0; i < cards.size(); i++) {
            boolean hit = needle.isEmpty() || keys.get(i).contains(needle);
            cards.get(i).setVisibility(hit ? View.VISIBLE : View.GONE);
            if (hit) {
                shown++;
            }
        }
        emptyNote.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }
}
