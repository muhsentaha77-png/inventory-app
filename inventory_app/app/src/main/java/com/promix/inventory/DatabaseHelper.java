package com.promix.inventory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "inventory.db";
    private static final int DB_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL, role TEXT NOT NULL)");
        db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, category TEXT, sku TEXT UNIQUE, quantity INTEGER NOT NULL DEFAULT 0, min_quantity INTEGER NOT NULL DEFAULT 5, notes TEXT)");
        db.execSQL("CREATE TABLE movements (id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, type TEXT NOT NULL, qty INTEGER NOT NULL, username TEXT, created_at TEXT NOT NULL, FOREIGN KEY(product_id) REFERENCES products(id))");
        db.execSQL("CREATE TABLE movement_sync (cloud_id TEXT PRIMARY KEY)");

        db.execSQL("INSERT INTO users(name,username,password,role) VALUES('المدير','admin','1234','admin')");
        db.execSQL("INSERT INTO products(name,category,sku,quantity,min_quantity,notes) VALUES('بطارية 200 أمبير','بطاريات','BAT-200',12,3,'مثال تجريبي')");
        db.execSQL("INSERT INTO products(name,category,sku,quantity,min_quantity,notes) VALUES('لوح طاقة 550 واط','ألواح طاقة','PV-550',20,5,'مثال تجريبي')");
        db.execSQL("INSERT INTO products(name,category,sku,quantity,min_quantity,notes) VALUES('انفرتر 5 كيلو','انفرترات','INV-5K',7,2,'مثال تجريبي')");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("CREATE TABLE IF NOT EXISTS movement_sync (cloud_id TEXT PRIMARY KEY)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS movements");
        db.execSQL("DROP TABLE IF EXISTS products");
        db.execSQL("DROP TABLE IF EXISTS users");
        onCreate(db);
    }

    public boolean login(String username, String password) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id FROM users WHERE username=? AND password=?", new String[]{username, password});
        boolean ok = c.moveToFirst();
        c.close();
        return ok;
    }

    public String getUserRole(String username) { Cursor c=getReadableDatabase().rawQuery("SELECT role FROM users WHERE username=?",new String[]{username}); String role=""; if(c.moveToFirst()) role=c.getString(0); c.close(); return role; }

    public Cursor getProducts(String search) {
        SQLiteDatabase db = getReadableDatabase();
        if (search == null || search.trim().isEmpty()) {
            return db.rawQuery("SELECT * FROM products ORDER BY name", null);
        }
        String q = "%" + search.trim() + "%";
        return db.rawQuery("SELECT * FROM products WHERE name LIKE ? OR category LIKE ? OR sku LIKE ? ORDER BY name", new String[]{q,q,q});
    }

    public Cursor getLowStock() {
        return getReadableDatabase().rawQuery("SELECT * FROM products WHERE quantity <= min_quantity ORDER BY quantity ASC", null);
    }

    public long addProduct(String name, String category, String sku, int qty, int minQty, String notes) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("category", category);
        v.put("sku", sku);
        v.put("quantity", qty);
        v.put("min_quantity", minQty);
        v.put("notes", notes);
        long id = getWritableDatabase().insert("products", null, v);
        if (id > 0) {
            Map<String, Object> product = new HashMap<>();
            product.put("id", id);
            product.put("name", name);
            product.put("category", category);
            product.put("sku", sku);
            product.put("quantity", qty);
            product.put("min_quantity", minQty);
            product.put("notes", notes);
            FirebaseFirestore.getInstance().collection("products").document(sku).set(product);
        }
        return id;
    }

    public boolean deleteProduct(long id) {
        SQLiteDatabase db = getWritableDatabase();

        Cursor c = db.rawQuery("SELECT sku FROM products WHERE id=?", new String[]{String.valueOf(id)});
        String sku = c.moveToFirst() ? c.getString(0) : null;
        c.close();

        db.delete("movements", "product_id=?", new String[]{String.valueOf(id)});
        boolean deleted = db.delete("products", "id=?", new String[]{String.valueOf(id)}) > 0;

        if (deleted && sku != null && !sku.isEmpty()) {
            FirebaseFirestore.getInstance().collection("products")
                .whereEqualTo("sku", sku)
                .get()
                .addOnSuccessListener(q -> {
                    for (com.google.firebase.firestore.DocumentSnapshot d : q.getDocuments()) {
                        d.getReference().delete();
                    }
                });
        }

        return deleted;
    }

    public boolean moveStock(long productId, int delta, String type, String username) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT quantity, sku FROM products WHERE id=?",
                    new String[]{String.valueOf(productId)}
            );

            if (!c.moveToFirst()) {
                c.close();
                return false;
            }

            int current = c.getInt(0);
            String sku = c.getString(1);
            c.close();

            int next = current + delta;
            if (next < 0) return false;

            ContentValues p = new ContentValues();
            p.put("quantity", next);
            db.update("products", p, "id=?",
                    new String[]{String.valueOf(productId)});

            final String movementSku = sku == null ? "" : sku.trim();

            if (!movementSku.isEmpty()) {
                FirebaseFirestore.getInstance()
                        .collection("products")
                        .whereEqualTo("sku", movementSku)
                        .get()
                        .addOnSuccessListener(querySnapshot -> {
                            for (com.google.firebase.firestore.DocumentSnapshot doc :
                                    querySnapshot.getDocuments()) {
                                doc.getReference().update(
                                        "quantity", next,
                                        "updated_at", System.currentTimeMillis()
                                );
                            }
                        })
                        .addOnFailureListener(e ->
                                android.util.Log.e(
                                        "FIREBASE_SYNC",
                                        "PRODUCT UPDATE FAILED sku=" + movementSku,
                                        e
                                ));
            }

            String createdAt = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
            ).format(new Date());

            ContentValues m = new ContentValues();
            m.put("product_id", productId);
            m.put("type", type);
            m.put("qty", Math.abs(delta));
            m.put("username", username);
            m.put("created_at", createdAt);

            long movementId = db.insert("movements", null, m);
            if (movementId == -1) return false;

            String cloudId = java.util.UUID.randomUUID().toString();

            ContentValues mark = new ContentValues();
            mark.put("cloud_id", cloudId);
            db.insertWithOnConflict(
                    "movement_sync",
                    null,
                    mark,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            Map<String, Object> movement = new HashMap<>();
            movement.put("sku", movementSku);
            movement.put("type", type);
            movement.put("qty", Math.abs(delta));
            movement.put("username", username);
            movement.put("created_at", createdAt);
            movement.put("created_at_ms", System.currentTimeMillis());

            FirebaseFirestore.getInstance()
                    .collection("movements")
                    .document(cloudId)
                    .set(movement)
                    .addOnFailureListener(e ->
                            android.util.Log.e(
                                    "MOVEMENT_SYNC",
                                    "UPLOAD FAILED " + cloudId,
                                    e
                            ));

            db.setTransactionSuccessful();
            return true;

        } finally {
            db.endTransaction();
        }
    }

    public void upsertMovementFromCloud(
            String cloudId,
            String sku,
            String type,
            int qty,
            String username,
            String createdAt
    ) {
        if (cloudId == null || cloudId.trim().isEmpty()) return;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();

        try {
            Cursor known = db.rawQuery(
                    "SELECT cloud_id FROM movement_sync WHERE cloud_id=? LIMIT 1",
                    new String[]{cloudId}
            );

            boolean exists = known.moveToFirst();
            known.close();

            if (exists) {
                db.setTransactionSuccessful();
                return;
            }

            Cursor p = db.rawQuery(
                    "SELECT id FROM products WHERE sku=? LIMIT 1",
                    new String[]{sku == null ? "" : sku}
            );

            if (!p.moveToFirst()) {
                p.close();
                db.setTransactionSuccessful();
                return;
            }

            long productId = p.getLong(0);
            p.close();

            ContentValues m = new ContentValues();
            m.put("product_id", productId);
            m.put("type", type == null ? "" : type);
            m.put("qty", qty);
            m.put("username", username == null ? "" : username);
            m.put("created_at", createdAt == null ? "" : createdAt);

            long inserted = db.insert("movements", null, m);

            if (inserted != -1) {
                ContentValues mark = new ContentValues();
                mark.put("cloud_id", cloudId);

                db.insertWithOnConflict(
                        "movement_sync",
                        null,
                        mark,
                        SQLiteDatabase.CONFLICT_IGNORE
                );
            }

            db.setTransactionSuccessful();

        } finally {
            db.endTransaction();
        }
    }

    public Cursor getMovements() {
        return getReadableDatabase().rawQuery(
                "SELECT m.id,p.name,m.type,m.qty,m.username,m.created_at FROM movements m JOIN products p ON p.id=m.product_id ORDER BY m.id DESC LIMIT 100", null);
    }

    public long addUser(String name, String username, String password, String role) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("username", username);
        v.put("password", password);
        v.put("role", role);
        return getWritableDatabase().insert("users", null, v);
    }

    public Cursor getUsers() {
        return getReadableDatabase().rawQuery("SELECT id,name,username,role FROM users ORDER BY name", null);
    }

    public int productCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM products", null);
        c.moveToFirst(); int n = c.getInt(0); c.close(); return n;
    }

    public int totalUnits() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(quantity),0) FROM products", null);
        c.moveToFirst(); int n = c.getInt(0); c.close(); return n;
    }

    public int lowStockCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM products WHERE quantity <= min_quantity", null);
        c.moveToFirst(); int n = c.getInt(0); c.close(); return n;
    }

    public long upsertProductFromCloud(String name, String category, String sku,
                                       int qty, int minQty, String notes) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("category", category);
        v.put("sku", sku);
        v.put("quantity", qty);
        v.put("min_quantity", minQty);
        v.put("notes", notes);

        String safeSku = sku == null ? "" : sku.trim();
        Cursor c;

        if (!safeSku.isEmpty()) {
            c = db.rawQuery(
                    "SELECT id FROM products WHERE sku=? LIMIT 1",
                    new String[]{safeSku});
        } else {
            c = db.rawQuery(
                    "SELECT id FROM products WHERE name=? AND category=? LIMIT 1",
                    new String[]{name, category});
        }

        try {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                db.update("products", v, "id=?",
                        new String[]{String.valueOf(id)});
                return id;
            }
        } finally {
            c.close();
        }

        return db.insert("products", null, v);
    }


    public void deleteLocalProductsNotInCloud(java.util.Set<String> cloudSkus) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id, sku FROM products", null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String sku = c.getString(1);
                String safeSku = sku == null ? "" : sku.trim();

                if (!safeSku.isEmpty() && !cloudSkus.contains(safeSku)) {
                    db.delete("movements", "product_id=?", new String[]{String.valueOf(id)});
                    db.delete("products", "id=?", new String[]{String.valueOf(id)});
                }
            }
        } finally {
            c.close();
        }
    }

}
