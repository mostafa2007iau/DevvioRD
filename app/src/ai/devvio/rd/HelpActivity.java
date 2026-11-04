package ai.devvio.rd;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** In-app documentation (Persian + English). */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.column(this);
        int pad = Ui.dp(this, 18);
        col.setPadding(pad, Ui.dp(this, 24), pad, pad);

        TextView back = Ui.text(this, "‹ بازگشت", 14, Ui.ACCENT, true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        col.addView(back);
        Ui.addTop(col, Ui.title(this, "راهنما"), 12);

        section(col, "۱. اجرای سرور",
                "روی کامپیوتر (لینوکس/مک/ویندوز) فایل devviord را اجرا کنید:\n\n"
                        + "devviord --token 1234\n\n"
                        + "آدرس‌هایی که چاپ می‌شود (مثلاً http://192.168.1.10:5900) را در اپ وارد کنید. "
                        + "پورت پیش‌فرض 5900 است. برای دسترسی از اینترنت، پورت را روی روتر باز کنید "
                        + "و حتماً --token بگذارید.");

        section(col, "۲. حالت لمسی (Touch)",
                "لمس مستقیم صفحه = کلیک در همان نقطه.\n"
                        + "کشیدن انگشت = درگ.\n"
                        + "دو انگشت = اسکرول عمودی/افقی.\n"
                        + "دو ضربه سریع = دابل‌کلیک.");

        section(col, "۳. حالت موس (Mouse)",
                "کشیدن انگشت روی صفحه = حرکت نشانگر موس (مثل تاچ‌پد).\n"
                        + "یک ضربه = کلیک چپ.\n"
                        + "دو ضربه = دابل‌کلیک.\n"
                        + "حساسیت را از تنظیمات تغییر دهید.");

        section(col, "۴. کیبورد",
                "دکمه ⌨ صفحه‌کلید اندروید را باز می‌کند. هر چیزی که تایپ کنید (فارسی، انگلیسی، "
                        + "ایموجی) به صورت متن UTF-8 به سرور ارسال می‌شود؛ کلیدهای Backspace/Enter هم "
                        + "پشتیبانی می‌شوند. کیبورد بلوتوث هم کار می‌کند.");

        section(col, "۵. کلیپ‌بورد",
                "برای فرستادن متن گوشی به سرور: متن را کپی کنید و دکمه ⎘ را بزنید.\n"
                        + "برای آوردن متن سرور به گوشی: دکمه ⤓ را بزنید.\n"
                        + "تغییرات کلیپ‌بورد سرور به صورت خودکار روی گوشی هم کپی می‌شوند.");

        section(col, "۶. انتقال فایل",
                "دکمه ⇄ صفحه فایل‌ها را باز می‌کند:\n"
                        + "• آپلود: فایل را از گوشی انتخاب کنید تا در پوشه سرور ذخیره شود.\n"
                        + "• دانلود: روی فایل در سرور بزنید تا در پوشه Downloads/DevvioRD ذخیره شود.\n"
                        + "• ساخت پوشه، تغییر نام و حذف هم ممکن است.");

        section(col, "۷. نکات اتصال",
                "اگر در شبکه محلی وصل نمی‌شوید:\n"
                        + "• آدرس IP کامپیوتر را بررسی کنید.\n"
                        + "• فایروال باید پورت 5900 را باز کند.\n"
                        + "• گوشی و کامپیوتر باید در یک شبکه باشند.\n"
                        + "• اگر توکن گذاشته‌اید، همان را در اپ وارد کنید.");

        section(col, "English quick start",
                "1) Run `devviord --token 1234` on the host.\n"
                        + "2) Enter the printed address in the app.\n"
                        + "3) Use TOUCH mode for direct taps or MOUSE mode for trackpad-style control.\n"
                        + "4) Use ⌨ for the keyboard, ⎘/⤓ for clipboard, ⇄ for file transfer.");

        sv.addView(col);
        return sv;
    }

    private void section(LinearLayout col, String title, String body) {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.roundRect(Ui.SURFACE, 16, this));
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        c.addView(Ui.text(this, title, 14, Ui.ACCENT, true));
        TextView t = Ui.text(this, body, 13, Ui.TEXT, false);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.addTop(c, t, 8);
        Ui.addTop(col, c, 14);
    }

    private LinearLayout.LayoutParams withTop(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        v.setLayoutParams(lp);
        return lp;
    }
}
