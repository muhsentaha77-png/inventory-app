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
        db.execSQL("CREATE TABLE IF NOT EXISTS app_flags (flag TEXT PRIMARY KEY)");

        // حذف التكرارات أولاً باستخدام username بعد التنظيف المنطقي
        db.execSQL(
                "DELETE FROM users WHERE id NOT IN (" +
                "SELECT MIN(id) FROM users " +
                "GROUP BY LOWER(TRIM(username))" +
                ")"
        );

        // بعد إزالة التكرارات يصبح تنظيف المسافات آمناً
        db.execSQL("UPDATE users SET username=TRIM(username)");
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


    public void uploadLegacyMovementsToFirestore() {
        SQLiteDatabase db = getReadableDatabase();

        Cursor c = db.rawQuery(
                "SELECT m.id, p.sku, m.type, m.qty, m.username, m.created_at " +
                "FROM movements m JOIN products p ON p.id=m.product_id",
                null
        );

        FirebaseFirestore cloud = FirebaseFirestore.getInstance();

        try {
            while (c.moveToNext()) {
                long localId = c.getLong(0);
                String sku = c.getString(1);
                String type = c.getString(2);
                int qty = c.getInt(3);
                String username = c.getString(4);
                String createdAt = c.getString(5);

                String safeSku = sku == null ? "" : sku.trim();
                if (safeSku.isEmpty()) continue;

                String cloudId =
                        "legacy_" +
                        safeSku.replace("/", "_") +
                        "_" +
                        localId;

                Cursor known = getReadableDatabase().rawQuery(
                        "SELECT cloud_id FROM movement_sync WHERE cloud_id=? LIMIT 1",
                        new String[]{cloudId}
                );

                boolean alreadyUploaded = known.moveToFirst();
                known.close();

                if (alreadyUploaded) {
                    continue;
                }

                Map<String, Object> movement = new HashMap<>();
                movement.put("sku", safeSku);
                movement.put("type", type == null ? "" : type);
                movement.put("qty", qty);
                movement.put("username", username == null ? "" : username);
                movement.put("created_at", createdAt == null ? "" : createdAt);
                movement.put("legacy_local_id", localId);
                movement.put("migrated_at_ms", System.currentTimeMillis());

                cloud.collection("movements")
                        .document(cloudId)
                        .set(movement)
                        .addOnSuccessListener(unused -> {
                            ContentValues mark = new ContentValues();
                            mark.put("cloud_id", cloudId);

                            getWritableDatabase().insertWithOnConflict(
                                    "movement_sync",
                                    null,
                                    mark,
                                    SQLiteDatabase.CONFLICT_IGNORE
                            );

                            android.util.Log.d(
                                    "MOVEMENT_MIGRATION",
                                    "Uploaded legacy movement: " + cloudId
                            );
                        })
                        .addOnFailureListener(e ->
                                android.util.Log.e(
                                        "MOVEMENT_MIGRATION",
                                        "Legacy movement upload failed: " + cloudId,
                                        e
                                ));
            }
        } finally {
            c.close();
        }
    }



    public void cleanupDuplicateMovementsOnce() {
        SQLiteDatabase db = getWritableDatabase();

        Cursor flag = db.rawQuery(
                "SELECT flag FROM app_flags WHERE flag='movement_dedupe_v1' LIMIT 1",
                null
        );

        boolean alreadyDone = flag.moveToFirst();
        flag.close();

        if (alreadyDone) return;

        db.beginTransaction();

        try {
            db.execSQL(
                    "DELETE FROM movements " +
                    "WHERE id NOT IN (" +
                    "SELECT MIN(id) FROM movements " +
                    "GROUP BY product_id, type, qty, IFNULL(username,''), created_at" +
                    ")"
            );

            ContentValues v = new ContentValues();
            v.put("flag", "movement_dedupe_v1");

            db.insertWithOnConflict(
                    "app_flags",
                    null,
                    v,
                    SQLiteDatabase.CONFLICT_IGNORE
            );

            db.setTransactionSuccessful();

            android.util.Log.d(
                    "MOVEMENT_CLEANUP",
                    "Duplicate movement cleanup completed"
            );

        } finally {
            db.endTransaction();
        }
    }


    public Cursor getMovements() {
        return getReadableDatabase().rawQuery(
                "SELECT m.id,p.name,m.type,m.qty,m.username,m.created_at FROM movements m JOIN products p ON p.id=m.product_id ORDER BY m.id DESC LIMIT 100", null);
    }

    public long addUser(String name, String username, String password, String role) {
        String safeUsername = username == null ? "" : username.trim();
        if (safeUsername.isEmpty()) return -1;

        SQLiteDatabase db = getWritableDatabase();

        Cursor existing = db.rawQuery(
                "SELECT id FROM users WHERE LOWER(TRIM(username))=LOWER(?) LIMIT 1",
                new String[]{safeUsername}
        );

        boolean exists = existing.moveToFirst();
        existing.close();

        if (exists) return -1;

        ContentValues v = new ContentValues();
        v.put("name", name == null ? "" : name.trim());
        v.put("username", safeUsername);
        v.put("password", password == null ? "" : password);
        v.put("role", role == null || role.trim().isEmpty() ? "موظف" : role.trim());

        long id = db.insert("users", null, v);

        if (id > 0) {
            Map<String, Object> user = new HashMap<>();
            user.put("name", name == null ? "" : name.trim());
            user.put("username", safeUsername);
            user.put("password", password == null ? "" : password);
            user.put("role", role == null || role.trim().isEmpty() ? "موظف" : role.trim());
            user.put("updated_at", System.currentTimeMillis());

            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(safeUsername)
                    .set(user);
        }

        return id;
    }

    public long upsertUserFromCloud(
            String name,
            String username,
            String password,
            String role
    ) {
        String safeUsername = username == null ? "" : username.trim();
        if (safeUsername.isEmpty()) return -1;

        SQLiteDatabase db = getWritableDatabase();

        ContentValues v = new ContentValues();
        v.put("name", name == null ? "" : name);
        v.put("username", safeUsername);
        v.put("password", password == null ? "" : password);
        v.put("role", role == null ? "موظف" : role);

        Cursor c = db.rawQuery(
                "SELECT id FROM users WHERE LOWER(TRIM(username))=LOWER(?) LIMIT 1",
                new String[]{safeUsername}
        );

        try {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                db.update(
                        "users",
                        v,
                        "id=?",
                        new String[]{String.valueOf(id)}
                );
                return id;
            }
        } finally {
            c.close();
        }

        return db.insert("users", null, v);
    }

    public Cursor getUsers() {
        return getReadableDatabase().rawQuery("SELECT id,name,username,role FROM users ORDER BY name", null);
    }

    public void deleteLocalUsersNotInCloud(java.util.Set<String> cloudUsernames) {
        SQLiteDatabase db = getWritableDatabase();

        Cursor c = db.rawQuery(
                "SELECT id, username FROM users",
                null
        );

        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String username = c.getString(1);
                String safeUsername = username == null ? "" : username.trim();

                if (safeUsername.isEmpty()) continue;
                if ("admin".equalsIgnoreCase(safeUsername)) continue;

                if (!cloudUsernames.contains(safeUsername)) {
                    db.delete(
                            "users",
                            "id=?",
                            new String[]{String.valueOf(id)}
                    );
                }
            }
        } finally {
            c.close();
        }
    }

    public boolean deleteUser(long id) {
        SQLiteDatabase db = getWritableDatabase();

        Cursor c = db.rawQuery(
                "SELECT username FROM users WHERE id=? LIMIT 1",
                new String[]{String.valueOf(id)}
        );

        String username = null;
        if (c.moveToFirst()) username = c.getString(0);
        c.close();

        if (username == null || username.trim().isEmpty()) return false;

        String safeUsername = username.trim();
        if ("admin".equalsIgnoreCase(safeUsername)) return false;

        boolean deleted = db.delete(
                "users", "id=?", new String[]{String.valueOf(id)}
        ) > 0;

        if (deleted) {
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(safeUsername)
                    .delete();
        }

        return deleted;
    }


    public void uploadAllUsersToFirestore() {
        SQLiteDatabase localDb = getReadableDatabase();

        Cursor c = localDb.rawQuery(
                "SELECT name, username, password, role FROM users",
                null
        );

        FirebaseFirestore cloud = FirebaseFirestore.getInstance();

        try {
            while (c.moveToNext()) {
                String name = c.getString(0);
                String username = c.getString(1);
                String password = c.getString(2);
                String role = c.getString(3);

                String safeUsername = username == null ? "" : username.trim();
                if (safeUsername.isEmpty()) continue;

                Map<String, Object> user = new HashMap<>();
                user.put("name", name == null ? "" : name);
                user.put("username", safeUsername);
                user.put("password", password == null ? "" : password);
                user.put("role", role == null ? "موظف" : role);
                user.put("updated_at", System.currentTimeMillis());

                cloud.collection("users")
                        .document(safeUsername)
                        .set(user)
                        .addOnSuccessListener(unused ->
                                android.util.Log.d(
                                        "USER_MIGRATION",
                                        "Uploaded local user: " + safeUsername
                                ))
                        .addOnFailureListener(e ->
                                android.util.Log.e(
                                        "USER_MIGRATION",
                                        "Failed local user upload: " + safeUsername,
                                        e
                                ));
            }
        } finally {
            c.close();
        }
    }



    public void uploadAllProductsToFirestore(Runnable onComplete) {
        SQLiteDatabase localDb = getReadableDatabase();

        Cursor c = localDb.rawQuery(
                "SELECT name, category, sku, quantity, min_quantity, notes FROM products",
                null
        );

        FirebaseFirestore cloud = FirebaseFirestore.getInstance();

        java.util.List<com.google.android.gms.tasks.Task<Void>> tasks =
                new java.util.ArrayList<>();

        try {
            while (c.moveToNext()) {
                String name = c.getString(0);
                String category = c.getString(1);
                String sku = c.getString(2);
                int quantity = c.getInt(3);
                int minQuantity = c.getInt(4);
                String notes = c.getString(5);

                String safeSku = sku == null ? "" : sku.trim();
                if (safeSku.isEmpty()) continue;

                Map<String, Object> product = new HashMap<>();
                product.put("name", name == null ? "" : name);
                product.put("category", category == null ? "" : category);
                product.put("sku", safeSku);
                product.put("quantity", quantity);
                product.put("min_quantity", minQuantity);
                product.put("notes", notes == null ? "" : notes);
                product.put("updated_at", System.currentTimeMillis());

                tasks.add(
                        cloud.collection("products")
                                .document(safeSku)
                                .set(product)
                );
            }
        } finally {
            c.close();
        }

        if (tasks.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        com.google.android.gms.tasks.Tasks.whenAllComplete(tasks)
                .addOnCompleteListener(task -> {
                    android.util.Log.d(
                            "RESTORE_SYNC",
                            "Restored products uploaded to Firestore"
                    );

                    if (onComplete != null) onComplete.run();
                });
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
