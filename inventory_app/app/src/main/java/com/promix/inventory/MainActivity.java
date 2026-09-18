package com.promix.inventory;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.database.Cursor;
import android.content.Intent;
import android.net.Uri;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;

public class MainActivity extends Activity {
    private DatabaseHelper db;
    private FirebaseFirestore firestore;
    private LinearLayout root;
    private String currentUser = "admin";
    private String currentRole = "admin";
    private int blue = Color.rgb(21,101,192);
    private int bg = Color.rgb(245,247,250);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        firestore = FirebaseFirestore.getInstance();

        java.util.Map<String, Object> testData = new java.util.HashMap<>();
        testData.put("message", "Firestore connected");
        testData.put("timestamp", System.currentTimeMillis());

        firestore.collection("test")
                .add(testData)
                .addOnSuccessListener(documentReference ->
                        android.util.Log.d("FIRESTORE_TEST", "SUCCESS: " + documentReference.getId()))
                .addOnFailureListener(e ->
                        android.util.Log.e("FIRESTORE_TEST", "FAILED", e));

        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        db = new DatabaseHelper(this);
        db.cleanupDuplicateMovementsOnce();
        syncProductsFromFirestore();
        syncMovementsFromFirestore();
        db.uploadLegacyMovementsToFirestore();
        showLogin();
    }

    private TextView text(String s, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(35,35,35));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(14,12,14,12); return t;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setTextSize(16); b.setAllCaps(false); return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextSize(16); e.setPadding(18,12,18,12); return e;
    }

    private void base(String title) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20,20,20,35); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView h = text(title, 26, true); h.setTextColor(blue); h.setGravity(Gravity.CENTER); root.addView(h);
        scroll.addView(root); setContentView(scroll);
    }

    private void showLogin() {
        base("نظام الجرد");
        TextView sub = text("إدارة البطاريات والألواح والانفرترات",17,false); sub.setGravity(Gravity.CENTER); root.addView(sub);
        Space sp = new Space(this); sp.setMinimumHeight(45); root.addView(sp);
        EditText user = input("اسم المستخدم"); user.setText("admin"); root.addView(user);
        EditText pass = input("كلمة المرور"); pass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); pass.setText("1234"); root.addView(pass);
        Button login = button("تسجيل الدخول"); root.addView(login);
        TextView hint = text("الحساب الافتراضي: admin / 1234",14,false); hint.setGravity(Gravity.CENTER); root.addView(hint);
        login.setOnClickListener(v -> {
            if (db.login(user.getText().toString().trim(), pass.getText().toString())) {
                currentUser = user.getText().toString().trim(); currentRole = "admin".equalsIgnoreCase(currentUser) ? "admin" : db.getUserRole(currentUser); showDashboard();
            } else Toast.makeText(this,"بيانات الدخول غير صحيحة",Toast.LENGTH_SHORT).show();
        });
    }

    private void showDashboard() {
        base("لوحة التحكم");
        LinearLayout stats = new LinearLayout(this); stats.setOrientation(LinearLayout.VERTICAL); stats.setPadding(10,15,10,15);
        stats.addView(text("عدد المنتجات: " + db.productCount(),19,true));
        stats.addView(text("إجمالي القطع: " + db.totalUnits(),19,true));
        TextView low = text("تنبيهات المخزون: " + db.lowStockCount(),19,true); if (db.lowStockCount()>0) low.setTextColor(Color.rgb(198,40,40)); stats.addView(low); root.addView(stats);
        Button products = button("📦 المنتجات والمخزون"); root.addView(products);
        Button lowBtn = button("⚠️ المواد القريبة من النفاد"); root.addView(lowBtn);
        Button history = button("🧾 سجل حركات المخزون"); root.addView(history);
        Button users = button("👥 الموظفون"); if ("admin".equalsIgnoreCase(currentRole)) root.addView(users);
        Button backup = button("💾 نسخة احتياطية"); if ("admin".equalsIgnoreCase(currentRole)) root.addView(backup);
        Button restore = button("📤 استعادة النسخة الاحتياطية");
        if ("admin".equalsIgnoreCase(currentRole)) root.addView(restore);
        Button logout = button("تسجيل الخروج"); root.addView(logout);
        products.setOnClickListener(v -> showProducts("")); lowBtn.setOnClickListener(v -> showLowStock()); history.setOnClickListener(v -> showHistory()); users.setOnClickListener(v -> showUsers()); backup.setOnClickListener(v -> createBackup()); restore.setOnClickListener(v -> restoreBackup()); logout.setOnClickListener(v -> showLogin());
    }

    private void addBack() { Button b=button("← رجوع"); root.addView(b,0); b.setOnClickListener(v->showDashboard()); }

    private void showProducts(String search) {
        base("المنتجات"); addBack();
        EditText q=input("ابحث بالاسم أو الصنف أو الكود"); q.setText(search); root.addView(q);
        Button find=button("بحث"); root.addView(find); Button add=button("+ إضافة منتج جديد"); root.addView(add);
        find.setOnClickListener(v->showProducts(q.getText().toString())); add.setOnClickListener(v->productDialog());
        Cursor c=db.getProducts(search);
        while(c.moveToNext()) addProductCard(c);
        c.close();
    }

    private void addProductCard(Cursor c) {
        long id=c.getLong(c.getColumnIndexOrThrow("id"));
        String name=c.getString(c.getColumnIndexOrThrow("name")); String cat=c.getString(c.getColumnIndexOrThrow("category")); String sku=c.getString(c.getColumnIndexOrThrow("sku"));
        int qty=c.getInt(c.getColumnIndexOrThrow("quantity")); int min=c.getInt(c.getColumnIndexOrThrow("min_quantity"));
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(18,14,18,14); card.setBackgroundColor(Color.WHITE);
        TextView n=text(name,19,true); card.addView(n); card.addView(text("الصنف: "+cat+"   |   الكود: "+sku,14,false));
        TextView qt=text("الكمية الحالية: "+qty,18,true); if(qty<=min) qt.setTextColor(Color.rgb(198,40,40)); card.addView(qt);
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER);
        Button in=button("+ إدخال"); Button out=button("- إخراج"); Button del=button("حذف"); actions.addView(in); actions.addView(out); if ("admin".equalsIgnoreCase(currentRole)) actions.addView(del); card.addView(actions);
        if (!"admin".equalsIgnoreCase(currentRole)) del.setVisibility(View.GONE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0,10,0,10); root.addView(card,p);
        in.setOnClickListener(v->movementDialog(id,name,true)); out.setOnClickListener(v->movementDialog(id,name,false));
        del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("حذف المنتج").setMessage("هل تريد حذف "+name+"؟").setPositiveButton("حذف",(d,w)->{db.deleteProduct(id);showProducts("");}).setNegativeButton("إلغاء",null).show());
    }

    private void syncProductToFirestore(String name, String category, String sku, int qty, int minQty, String notes) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("name", name);
        data.put("category", category);
        data.put("sku", sku);
        data.put("quantity", qty);
        data.put("min_quantity", minQty);
        data.put("notes", notes);
        data.put("updated_at", System.currentTimeMillis());

        firestore.collection("products")
                .add(data)
                .addOnSuccessListener(doc ->
                        android.util.Log.d("FIRESTORE_PRODUCT", "SYNCED: " + doc.getId()))
                .addOnFailureListener(e ->
                        android.util.Log.e("FIRESTORE_PRODUCT", "SYNC FAILED", e));
    }

    private void productDialog() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(30,10,30,10);
        EditText name=input("اسم المنتج"); EditText cat=input("الصنف: بطاريات / ألواح طاقة / انفرترات"); EditText sku=input("الكود SKU"); EditText qty=input("الكمية الحالية"); EditText min=input("حد التنبيه"); EditText notes=input("ملاحظات");
        qty.setInputType(InputType.TYPE_CLASS_NUMBER); min.setInputType(InputType.TYPE_CLASS_NUMBER); l.addView(name);l.addView(cat);l.addView(sku);l.addView(qty);l.addView(min);l.addView(notes);
        new AlertDialog.Builder(this).setTitle("إضافة منتج").setView(l).setPositiveButton("حفظ",(d,w)->{
            try { long r=db.addProduct(name.getText().toString().trim(),cat.getText().toString().trim(),sku.getText().toString().trim(),Integer.parseInt(qty.getText().toString().trim().isEmpty()?"0":qty.getText().toString().trim()),Integer.parseInt(min.getText().toString().trim().isEmpty()?"5":min.getText().toString().trim()),notes.getText().toString().trim());
                if(r==-1) Toast.makeText(this,"الكود مستخدم أو البيانات غير صحيحة",Toast.LENGTH_LONG).show(); showProducts(""); } catch(Exception e){Toast.makeText(this,"تحقق من البيانات",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("إلغاء",null).show();
    }

    private void movementDialog(long id,String name,boolean incoming) {
        EditText qty=input("الكمية"); qty.setInputType(InputType.TYPE_CLASS_NUMBER); qty.setPadding(30,20,30,20);
        String title=incoming?"إدخال للمخزون":"إخراج من المخزون";
        new AlertDialog.Builder(this).setTitle(title+" - "+name).setView(qty).setPositiveButton("تأكيد",(d,w)->{
            try {int n=Integer.parseInt(qty.getText().toString()); boolean ok=db.moveStock(id,incoming?n:-n,incoming?"إدخال":"إخراج",currentUser); Toast.makeText(this,ok?"تم تسجيل الحركة":"الكمية غير متوفرة",Toast.LENGTH_SHORT).show(); showProducts("");} catch(Exception e){Toast.makeText(this,"أدخل رقماً صحيحاً",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("إلغاء",null).show();
    }

    private void showLowStock() {
        base("المواد القريبة من النفاد"); addBack(); Cursor c=db.getLowStock(); int count=0;
        while(c.moveToNext()){count++; addProductCard(c);} c.close(); if(count==0){TextView t=text("لا توجد مواد منخفضة المخزون ✅",18,true); t.setGravity(Gravity.CENTER); root.addView(t);} }

    private void showHistory() {
        base("سجل الحركات"); addBack(); Cursor c=db.getMovements();
        while(c.moveToNext()){
            String name=c.getString(1), type=c.getString(2), user=c.getString(4), date=c.getString(5); int qty=c.getInt(3);
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(15,8,15,8); card.setBackgroundColor(Color.WHITE);
            card.addView(text(type+" | "+name+" | "+qty+" قطعة",17,true)); card.addView(text(date+" | بواسطة: "+user,13,false));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,7,0,7);root.addView(card,p);
        } c.close();
    }

    private void showUsers() {
        if (!"admin".equalsIgnoreCase(currentRole)) { Toast.makeText(this, "هذه الصفحة للمدير فقط", Toast.LENGTH_SHORT).show(); return; }
        base("الموظفون"); addBack(); Button add=button("+ إضافة موظف"); root.addView(add); add.setOnClickListener(v->userDialog());
        Cursor c=db.getUsers(); while(c.moveToNext()){root.addView(text(c.getString(1)+" — "+c.getString(3)+" ("+c.getString(2)+")",17,true));} c.close();
    }

    private void userDialog() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(30,10,30,10);
        EditText name=input("اسم الموظف"), user=input("اسم المستخدم"), pass=input("كلمة المرور"), role=input("الصلاحية: موظف أو مدير"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(name);l.addView(user);l.addView(pass);l.addView(role);
        new AlertDialog.Builder(this).setTitle("موظف جديد").setView(l).setPositiveButton("حفظ",(d,w)->{long r=db.addUser(name.getText().toString(),user.getText().toString(),pass.getText().toString(),role.getText().toString().isEmpty()?"موظف":role.getText().toString());Toast.makeText(this,r==-1?"اسم المستخدم موجود":"تمت الإضافة",Toast.LENGTH_SHORT).show();showUsers();}).setNegativeButton("إلغاء",null).show();
    }


    private void createBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "inventory-backup.db");
        startActivityForResult(i, 9001);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 9001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();

            try {
                File dbFile = getDatabasePath("inventory.db");

                try (InputStream in = new FileInputStream(dbFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {

                    if (out == null) throw new Exception("Cannot open output");

                    byte[] buffer = new byte[8192];
                    int length;

                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }

                    out.flush();
                }

                Toast.makeText(this, "تم حفظ النسخة الاحتياطية بنجاح", Toast.LENGTH_LONG).show();

            } catch (Exception e) {
                Toast.makeText(this, "فشل حفظ النسخة الاحتياطية", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == 9002 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();

            try {
                db.close();

                File dbFile = getDatabasePath("inventory.db");
                File walFile = new File(dbFile.getPath() + "-wal");
                File shmFile = new File(dbFile.getPath() + "-shm");

                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dbFile)) {

                    if (in == null) throw new Exception("Cannot open backup");

                    byte[] buffer = new byte[8192];
                    int length;

                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }

                if (walFile.exists()) walFile.delete();
                if (shmFile.exists()) shmFile.delete();

                db = new DatabaseHelper(this);

                db.uploadAllProductsToFirestore(() -> runOnUiThread(() -> {
                    Toast.makeText(
                            this,
                            "تمت استعادة النسخة الاحتياطية ومزامنتها بنجاح",
                            Toast.LENGTH_LONG
                    ).show();

                    showDashboard();
                }));

            } catch (Exception e) {
                db = new DatabaseHelper(this);
                Toast.makeText(this, "فشل استعادة النسخة الاحتياطية", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void restoreBackup() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("تأكيد الاستعادة")
                .setMessage("هل أنت متأكد من استعادة النسخة الاحتياطية؟ سيتم استبدال البيانات الحالية.")
                .setPositiveButton("استعادة", (dialog, which) -> openBackupFile())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openBackupFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, 9002);
    }

    @Override public void onBackPressed() { showDashboard(); }

    private void syncMovementsFromFirestore() {
        firestore.collection("movements")
                .addSnapshotListener((querySnapshot, error) -> {
                    if (error != null) {
                        android.util.Log.e(
                                "MOVEMENT_SYNC",
                                "Realtime movement sync failed",
                                error
                        );
                        return;
                    }

                    if (querySnapshot == null) return;

                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        String sku = doc.getString("sku");
                        String type = doc.getString("type");
                        String username = doc.getString("username");
                        String createdAt = doc.getString("created_at");
                        Long qty = doc.getLong("qty");

                        db.upsertMovementFromCloud(
                                doc.getId(),
                                sku == null ? "" : sku,
                                type == null ? "" : type,
                                qty == null ? 0 : qty.intValue(),
                                username == null ? "" : username,
                                createdAt == null ? "" : createdAt
                        );
                    }
                });
    }

    private void syncProductsFromFirestore() {
        firestore.collection("products")
                .addSnapshotListener((querySnapshot, error) -> {
                    if (error != null) {
                        android.util.Log.e("FIRESTORE_SYNC",
                                "Realtime sync failed", error);
                        return;
                    }
                    if (querySnapshot == null) return;
                    int count = 0;
                    java.util.Set<String> cloudSkus = new java.util.HashSet<>();

                    for (DocumentSnapshot doc : querySnapshot.getDocuments()) {
                        String name = doc.getString("name");
                        String category = doc.getString("category");
                        String sku = doc.getString("sku");
                        String notes = doc.getString("notes");

                        Long quantity = doc.getLong("quantity");
                        Long minQuantity = doc.getLong("min_quantity");

                        if (name == null) name = "";
                        if (category == null) category = "";
                        if (sku == null) sku = "";
                        if (notes == null) notes = "";

                        if (!sku.trim().isEmpty()) cloudSkus.add(sku.trim());

                        int qty = quantity == null ? 0 : quantity.intValue();
                        int minQty = minQuantity == null ? 0 : minQuantity.intValue();

                        db.upsertProductFromCloud(
                                name,
                                category,
                                sku,
                                qty,
                                minQty,
                                notes
                        );

                        count++;
                    }

                    db.deleteLocalProductsNotInCloud(cloudSkus);

                    android.util.Log.d(
                            "FIRESTORE_SYNC",
                            "Downloaded products: " + count
                    );
                });
    }

}
