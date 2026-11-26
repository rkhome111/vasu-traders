package com.vasutraders.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    private static final int INK = 0xff1b2b22, MUTED = 0xff53665a, ACCENT = 0xff087c35,
            CANVAS = 0xfff5f7f3, WHITE = 0xffffffff, STROKE = 0xffe0e8de;
    private static final String CATALOG = "Materials", ORDERS = "Orders", CART = "Cart";
    private final TraderApi api = new TraderApi();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private TraderSession storage;
    private JSONObject session;
    private LinearLayout root, body;
    private TextView status;
    private boolean busy;
    private String tab = "", search = "";
    private int page;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        storage = new TraderSession(this);
        session = storage.read();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy) return;
                if (session != null && !tab.equals(homeTab())) navigate(homeTab());
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        if (session == null) showAuth(false); else navigate(homeTab());
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private String homeTab() { return CATALOG; }
    private JSONObject account() { return session.optJSONObject("account"); }
    private String token() { return session == null ? null : session.optString("token"); }

    private void showAuth(boolean signup) {
        if (signup) {
            showPasswordSetup(true, "");
            return;
        }
        tab = "";
        shell("Sign in", false);
        LinearLayout card = card();
        EditText mobile = field(card, "Mobile number", "10-digit mobile number", InputType.TYPE_CLASS_PHONE, "");
        mobile.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        EditText password = passwordField(card, false);
        addButton(card, "Sign in", true, () -> {
            String number = value(mobile);
            String secret = password.getText().toString();
            if (number.isEmpty()) { mobile.setError("Enter your mobile number."); return; }
            if (secret.isEmpty()) { password.setError("Enter your password."); return; }
            request("POST", "/auth/password", object("role", "CUSTOMER", "mobileNo", number, "password", secret),
                    this::finishSignIn);
        });
        addButton(card, "Set or reset password", false, () -> showPasswordSetup(false, value(mobile)));
        addButton(card, "Create an account", false, () -> showAuth(true));
        addButton(card, "Privacy policy", false, () -> openAccountPage("#privacy"));
    }

    private void showPasswordSetup(boolean signup, String number) {
        tab = "";
        shell(signup ? "Create an account" : "Set or reset password", false);
        LinearLayout card = card();
        if (signup) heading(card, "Your details");
        paragraph(card, "We’ll send an SMS code to verify your mobile number.");
        EditText mobile = field(card, "Mobile number", "10-digit mobile number", InputType.TYPE_CLASS_PHONE, number);
        EditText name = signup ? field(card, "Full name", "", textInput(), "") : null;
        EditText address = signup ? field(card, "Delivery address", "", addressInput(), "") : null;
        EditText email = signup ? field(card, "Email (optional)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "") : null;
        addButton(card, "Send verification code", true, () -> {
            String input = value(mobile);
            if (input.isEmpty()) { mobile.setError("Enter your mobile number."); return; }
            if (signup && value(name).isEmpty()) { name.setError("Enter a name."); return; }
            if (signup && value(address).isEmpty()) { address.setError("Enter an address."); return; }
            JSONObject profile = signup ? object("name", value(name), "address", value(address), "email", value(email)) : object();
            request("POST", "/auth/start", object("mobileNo", input, "role", "CUSTOMER", "signup", signup),
                    response -> showCode(input, ((JSONObject) response).optString("challengeId"), profile, signup));
        });
        addButton(card, "Back to sign in", false, () -> showAuth(false));
        addButton(card, "Privacy policy", false, () -> openAccountPage("#privacy"));
    }

    private void showCode(String mobile, String challenge, JSONObject profile, boolean signup) {
        shell("Verify your mobile", false);
        LinearLayout card = card();
        paragraph(card, "Enter the SMS code sent to " + mobile + " and choose a password for future sign-ins.");
        EditText code = field(card, "SMS verification code", "", InputType.TYPE_CLASS_NUMBER, "");
        code.setAutofillHints("smsOTPCode");
        EditText password = passwordField(card, true);
        addButton(card, signup ? "Create account" : "Save password and sign in", true, () -> {
            String secret = password.getText().toString();
            if (!value(code).matches("[0-9]{4,10}")) { code.setError("Enter the verification code."); return; }
            if (secret.trim().isEmpty() || secret.codePointCount(0, secret.length()) < 15) {
                password.setError("Enter a password of at least 15 characters.");
                return;
            }
            JSONObject data = object("challengeId", challenge, "code", value(code), "name", profile.opt("name"),
                    "address", profile.opt("address"), "email", profile.opt("email"), "password", secret);
            request("POST", "/auth/verify", data, this::finishSignIn);
        });
        addButton(card, "Change details or request a new code", false, () -> showPasswordSetup(signup, mobile));
        addButton(card, "Back to sign in", false, () -> showAuth(false));
    }

    private EditText passwordField(LinearLayout card, boolean setup) {
        EditText password = field(card, setup ? "New password" : "Password", setup ? "At least 15 characters" : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        password.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        password.setAutofillHints(setup ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        return password;
    }

    private void finishSignIn(Object response) {
        session = (JSONObject) response;
        if (saveSession()) navigate(homeTab());
        else {
            session = null;
            showAuth(false);
            report("Unable to save your session. Please sign in again.", true);
        }
    }

    private void navigate(String next) {
        tab = next;
        page = 0;
        if (tab.equals(CATALOG)) catalog();
        else if (tab.equals(CART)) cart(false);
        else orders();
    }

    private void catalog() {
        shell(CATALOG, true);
        LinearLayout filter = card();
        EditText keyword = field(filter,"Find a material","Sand, cement or stone", textInput(), search);
        addButton(filter,"Search",false, () -> { search = value(keyword); page = 0; catalog(); });
        String query;
        try { query = URLEncoder.encode(search, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
        request("GET", "/customer/catalog?page=" + page + "&q=" + query, null, response -> {
            JSONArray rows = (JSONArray) response;
            if (rows.length() == 0) paragraph(card(), "No materials match this search.");
            for (int i = 0; i < rows.length(); i++) material(rows.optJSONObject(i));
            pagination(rows.length(), this::catalog);
        });
    }

    private void material(JSONObject item) {
        LinearLayout card = card();
        long id = item.optLong("id");
        String name = item.optString("name"), unit = item.optString("unit");
        BigDecimal available = decimal(item,"availableQuantity");
        heading(card, name);
        paragraph(card, money(decimal(item,"unitPrice")) + " per " + unitLabel(unit));
        paragraph(card, available.signum() == 0 ? "Out of stock" : plain(available) + " " + unitLabel(unit) + " available");
        if (available.signum() == 0) return;
        JSONObject saved = cartItems().optJSONObject(Long.toString(id));
        EditText amount = field(card, "Quantity (" + unitLabel(unit) + ")", "Enter quantity", quantityInput(unit),
                saved == null ? "" : saved.optString("quantity"));
        addButton(card, saved == null ? "Add to cart" : "Update cart", true, () -> {
            BigDecimal qty = quantity(amount, unit, false);
            if (qty == null) return;
            if (qty.compareTo(available) > 0) { amount.setError("Only " + plain(available) + " available."); return; }
            var lines = cartItems();
            if (!lines.has(Long.toString(id)) && lines.length() >= 30) { report("The cart can contain up to 30 materials.", true); return; }
            put(lines, Long.toString(id), object("materialId",id, "name",name, "unit",unit,
                    "quantity", plain(qty), "unitPrice", plain(decimal(item,"unitPrice"))));
            clearCheckoutKey();
            if (saveSession()) report(name + " added to your cart.", false);
        });
    }

    private void cart(boolean review) {
        shell(review ? "Review order" : CART, true);
        JSONObject lines = cartItems();
        if (lines.length() == 0) {
            LinearLayout empty = card();
            paragraph(empty, "Your cart is empty.");
            addButton(empty, "Browse materials", true, () -> navigate(CATALOG));
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Iterator<String> entries = lines.keys(); entries.hasNext();) {
            String key = entries.next();
            JSONObject item = lines.optJSONObject(key);
            String unit = item.optString("unit");
            BigDecimal cost = decimal(item,"quantity").multiply(decimal(item,"unitPrice")).setScale(2, RoundingMode.HALF_UP);
            total = total.add(cost);
            LinearLayout card = card();
            heading(card, item.optString("name"));
            paragraph(card, item.optString("quantity") + " " + unitLabel(unit) + " · " + money(decimal(item,"unitPrice")) + " per " + unitLabel(unit));
            paragraph(card, money(cost));
            if (!review) {
                EditText amount = field(card,"Quantity (" + unitLabel(unit) + ")","", quantityInput(unit), item.optString("quantity"));
                addButton(card,"Update quantity",false, () -> {
                    BigDecimal qty = quantity(amount, unit, false);
                    if (qty == null) return;
                    put(item, "quantity", plain(qty));
                    clearCheckoutKey();
                    if (saveSession()) cart(false);
                });
                addButton(card, "Remove " + item.optString("name"), false, () -> {
                    lines.remove(key);
                    clearCheckoutKey();
                    if (saveSession()) cart(false);
                });
            }
        }
        LinearLayout summary = card();
        heading(summary, "Total " + money(total));
        if (!review) {
            addButton(summary,"Review checkout",true, () -> cart(true));
            return;
        }
        EditText address = field(summary, "Delivery address", "", addressInput(),
                session.optString("orderAddress", account().optString("address")));
        paragraph(summary, "The office will assign a delivery date after you place the order.");
        addButton(summary,"Place order",true, () -> checkout(value(address)));
        addButton(summary,"Back to cart",false, () -> cart(false));
    }

    private void checkout(String address) {
        if (address.isEmpty() || address.length() > 500) { report("Enter a delivery address of up to 500 characters.", true); return; }
        if (!address.equals(session.optString("orderAddress")) || !session.has("checkoutKey")) {
            put(session,"checkoutKey", UUID.randomUUID().toString());
            put(session,"orderAddress",address);
        }
        if (!saveSession()) return;
        JSONArray lines = new JSONArray();
        JSONObject items = cartItems();
        for (Iterator<String> it = items.keys(); it.hasNext();) {
            JSONObject item = items.optJSONObject(it.next());
            lines.put(object("materialId", item.optLong("materialId"), "quantity", item.optString("quantity"),
                    "expectedUnitPrice", item.optString("unitPrice")));
        }
        request("POST", "/customer/orders", object("requestKey",session.optString("checkoutKey"), "deliveryAddress",address, "items",lines),
                response -> {
                    JSONObject order = (JSONObject) response;
                    session.remove("cart");
                    clearCheckoutKey();
                    saveSession();
                    tab = ORDERS;
                    shell("Order placed",true);
                    LinearLayout confirmed = card();
                    heading(confirmed, order.optString("orderNumber"));
                    paragraph(confirmed,"Your order has been placed. We’ll assign a delivery date soon.");
                    addButton(confirmed,"View my orders",true, () -> navigate(ORDERS));
                });
    }

    private void orders() {
        shell(ORDERS,true);
        addButton(body,"Refresh orders",false,this::orders);
        request("GET", "/customer/orders?page=" + page,null,response -> {
            JSONArray rows = (JSONArray) response;
            if (rows.length() == 0) paragraph(card(), "No orders on this page.");
            for (int i = 0; i < rows.length(); i++) order(rows.optJSONObject(i));
            pagination(rows.length(), this::orders);
        });
    }

    private void order(JSONObject order) {
        LinearLayout card = card();
        heading(card, order.optString("orderNumber"));
        String state = switch (order.optString("status")) {
            case "SCHEDULED" -> "Scheduled";
            case "DELIVERED" -> "Delivered";
            default -> "Awaiting delivery date";
        };
        paragraph(card, state);
        if (!order.isNull("deliveryDate")) {
            String day = LocalDate.parse(order.optString("deliveryDate")).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
            paragraph(card, "Delivery " + day);
        }
        paragraph(card, order.optString("deliveryAddress"));
        JSONArray items = order.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            paragraph(card, item.optString("name") + " · " + plain(decimal(item,"quantity")) + " " + unitLabel(item.optString("unit")));
        }
        heading(card, money(decimal(order,"total")));
    }

    private JSONObject cartItems() {
        JSONObject items = session.optJSONObject("cart");
        if (items == null) { items = new JSONObject(); put(session,"cart",items); }
        return items;
    }

    private void clearCheckoutKey() { session.remove("checkoutKey"); }

    private void showAccount() {
        tab = "Account";
        shell(tab, true);
        LinearLayout card = card();
        addButton(card, "Privacy policy", false, () -> openAccountPage("#privacy"));
        addButton(card, "Request account deletion", false, () -> openAccountPage("#delete-account"));
        addButton(card, "Sign out", false, this::signOut);
    }

    private void openAccountPage(String anchor) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.ACCOUNT_URL + anchor)));
        } catch (ActivityNotFoundException unavailable) {
            report("Open " + BuildConfig.ACCOUNT_URL + " in a browser.", true);
        }
    }

    private void signOut() {
        new AlertDialog.Builder(this).setTitle("Sign out?")
                .setNegativeButton("Stay signed in", null).setPositiveButton("Sign out", (dialog, which) -> {
                    request("DELETE","/auth/session",null, ignored -> clearSession());
                }).show();
    }

    private void clearSession() {
        storage.clear();
        session = null;
        search = "";
        showAuth(false);
    }

    private void request(String method, String path, JSONObject body, Consumer<Object> success) {
        if (busy) return;
        busy = true;
        report("Working…", false);
        String token = token();
        worker.execute(() -> {
            try {
                Object result = api.call(method,path,body,token);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    report("", false);
                    try { success.accept(result); }
                    catch (RuntimeException invalid) { report("Unable to show this response. Refresh to retry.", true); }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    if (failure instanceof TraderApi.ApiError error && error.status == 401 && token != null) clearSession();
                    report(failure instanceof TraderApi.ApiError ? failure.getMessage() : "Unable to connect. Try again.", true);
                });
            }
        });
    }

    private boolean saveSession() {
        try { storage.write(session); return true; }
        catch (Exception failure) { report("Unable to save changes on this device.", true); return false; }
    }

    private void shell(String title, boolean signedIn) {
        root = column();
        root.setBackgroundColor(CANVAS);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            var ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom,ime.bottom));
            return insets;
        });
        LinearLayout header = column();
        header.setPadding(dp(20),dp(14),dp(20),dp(15));
        header.setBackgroundColor(0xff073725);
        header.addView(text(getString(R.string.app_name), 15, 0xffbae767, true));
        header.addView(text(title,26,WHITE,true));
        root.addView(header);
        if (signedIn) {
            LinearLayout navigation = new LinearLayout(this);
            navigation.setPadding(dp(12), dp(3), dp(12), dp(3));
            String[] tabs = {CATALOG,CART,ORDERS};
            for (String option : tabs) {
                Button control = button(option, option.equals(tab), () -> navigate(option));
                navigation.addView(control, new LinearLayout.LayoutParams(0, dp(48),1));
            }
            root.addView(navigation);
        }
        status = text("",14,MUTED,false);
        status.setPadding(dp(17),dp(8),dp(17),0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        root.addView(status);
        ScrollView scrolling = new ScrollView(this);
        scrolling.setFillViewport(true);
        body = column();
        body.setPadding(dp(14),dp(12),dp(14),dp(20));
        scrolling.addView(body);
        root.addView(scrolling, new LinearLayout.LayoutParams(-1,0,1));
        if (signedIn) {
            Button account = button("Account", false, this::showAccount);
            root.addView(account, new LinearLayout.LayoutParams(-1,dp(44)));
        }
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void pagination(int count, Runnable reload) {
        if (page > 0) addButton(body,"Previous page",false, () -> { page--; reload.run(); });
        if (count == 50) addButton(body,"Next page",false, () -> { page++; reload.run(); });
    }

    private void report(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? 0xffa32e25 : MUTED);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16),dp(14),dp(16),dp(14));
        card.setBackground(shape(WHITE,12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin = dp(12);
        body.addView(card,params);
        return card;
    }
    private android.graphics.drawable.GradientDrawable shape(int color,int radius) {
        var background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (color == WHITE) background.setStroke(dp(1),STROKE);
        return background;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null,android.graphics.Typeface.BOLD);
        return view;
    }
    private void heading(LinearLayout parent,String value) { parent.addView(text(value,19,INK,true)); }
    private void paragraph(LinearLayout parent,String value) {
        TextView view = text(value,14,MUTED,false);
        view.setPadding(0,dp(5),0,dp(6));
        parent.addView(view);
    }
    private TextView label(String value) {
        TextView label = text(value,14,INK,true);
        label.setPadding(0,dp(9),0,dp(5));
        return label;
    }
    private EditText field(LinearLayout parent, String title, String hint, int type, String value) {
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        TextView label = label(title);
        label.setLabelFor(input.getId());
        parent.addView(label);
        input.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        input.setInputType(type);
        input.setHint(hint);
        input.setText(value);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setMinHeight(dp(46));
        input.setGravity(Gravity.TOP);
        input.setPadding(dp(12),dp(11),dp(12),dp(10));
        input.setBackground(shape(CANVAS,8));
        parent.addView(input,new LinearLayout.LayoutParams(-1,-2));
        return input;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? WHITE : INK);
        button.setBackground(shape(primary ? ACCENT : WHITE,8));
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        return button;
    }
    private void addButton(LinearLayout parent,String label,boolean primary,Runnable action) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1,dp(48));
        layout.topMargin = dp(8);
        parent.addView(button(label,primary,action),layout);
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private static int textInput() { return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS; }
    private static int addressInput() { return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; }
    private static boolean wholeUnit(String unit) { return "TRACTOR".equals(unit) || "TRUCK".equals(unit); }
    private static int quantityInput(String unit) { return InputType.TYPE_CLASS_NUMBER | (wholeUnit(unit) ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL); }
    private static String value(EditText field) { return field.getText().toString().trim(); }
    private static BigDecimal decimal(JSONObject object,String key) { return new BigDecimal(object.optString(key,"0")); }
    private static String plain(BigDecimal amount) { return amount.stripTrailingZeros().toPlainString(); }
    private static String unitLabel(String unit) {
        return switch (unit) {
            case "TON" -> "Ton";
            case "TRACTOR" -> "Tractor";
            case "TRUCK" -> "Truck";
            default -> unit;
        };
    }
    private static BigDecimal quantity(EditText field,String unit,boolean allowZero) {
        String value = value(field);
        String pattern = wholeUnit(unit) ? "[0-9]{1,9}" : "[0-9]{1,9}(\\.[0-9]{1,3})?";
        if (!value.matches(pattern)) { field.setError(wholeUnit(unit) ? "Enter a whole quantity." : "Enter up to three decimal places."); return null; }
        BigDecimal quantity = new BigDecimal(value);
        if (!allowZero && quantity.signum() == 0) { field.setError("Quantity must be greater than zero."); return null; }
        return quantity;
    }
    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("en","IN"));
        format.setCurrency(Currency.getInstance("INR"));
        return format.format(amount);
    }
    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(result,(String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static void put(JSONObject object,String key,Object value) {
        try { object.put(key,value); }
        catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
    }
}
