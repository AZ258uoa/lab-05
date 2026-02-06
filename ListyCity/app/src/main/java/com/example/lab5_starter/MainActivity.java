package com.example.lab5_starter;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements CityDialogFragment.CityDialogListener {

    // UI
    private Button addCityButton;
    private ListView cityListView;

    // Data (UI cache) - Firestore is the source of truth
    private ArrayList<City> cityArrayList;
    private CityArrayAdapter cityArrayAdapter;


    private FirebaseFirestore db;
    private CollectionReference citiesRef;


    private float downX;
    private float downY;
    private int downPos;
    private boolean swiping;


    private int SWIPE_THRESHOLD_PX = 200;
    private float MAX_VERTICAL_SLOP = 100f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        addCityButton = findViewById(R.id.buttonAddCity);
        cityListView = findViewById(R.id.listviewCities);

        cityArrayList = new ArrayList<>();
        cityArrayAdapter = new CityArrayAdapter(this, cityArrayList);
        cityListView.setAdapter(cityArrayAdapter);


        db = FirebaseFirestore.getInstance();
        citiesRef = db.collection("cities");


        citiesRef.addSnapshotListener((value, error) -> {
            if (error != null) {
                Log.e("Firestore", "Snapshot listener error", error);
                return;
            }
            if (value == null) return;

            cityArrayList.clear();
            for (QueryDocumentSnapshot snapshot : value) {
                String name = snapshot.getString("name");
                String province = snapshot.getString("province");

                // Basic null safety
                if (name == null) name = "";
                if (province == null) province = "";

                cityArrayList.add(new City(name, province));
            }
            cityArrayAdapter.notifyDataSetChanged();
        });


        addCityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // For "add", we just open the dialog with a different tag
                CityDialogFragment fragment = new CityDialogFragment();
                fragment.show(getSupportFragmentManager(), "Add City");
            }
        });


        cityListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= cityArrayList.size()) return;

                City clickedCity = cityArrayList.get(position);

                String[] options = new String[]{"Edit", "Delete"};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(clickedCity.getName() + " (" + clickedCity.getProvince() + ")")
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                // Edit
                                CityDialogFragment.newInstance(clickedCity)
                                        .show(getSupportFragmentManager(), "City Details");
                            } else if (which == 1) {
                                // Delete
                                confirmAndDelete(clickedCity);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        cityListView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downPos = cityListView.pointToPosition((int) event.getX(), (int) event.getY());
                        swiping = false;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;

                        if (Math.abs(dx) > SWIPE_THRESHOLD_PX && Math.abs(dy) < MAX_VERTICAL_SLOP) {
                            swiping = true;
                            // Stop ListView from intercepting so the swipe feels smooth
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (swiping && downPos != ListView.INVALID_POSITION) {
                            if (downPos >= 0 && downPos < cityArrayList.size()) {
                                City cityToDelete = cityArrayList.get(downPos);
                                // You can delete directly, or use a confirm dialog.
                                deleteCityFromFirestore(cityToDelete);
                                Toast.makeText(MainActivity.this,
                                        "Deleted: " + cityToDelete.getName(),
                                        Toast.LENGTH_SHORT).show();
                            }
                            swiping = false;
                            return true; // consumed
                        }
                        swiping = false;
                        break;

                    case MotionEvent.ACTION_CANCEL:
                        swiping = false;
                        break;
                }
                return false; // let ListView handle other events (click/scroll)
            }
        });
    }



    @Override
    public void addCity(City city) {
        // Firestore is the source of truth. Listener will update UI.
        if (city == null) return;

        String name = safeTrim(city.getName());
        String province = safeTrim(city.getProvince());

        if (name.isEmpty()) {
            Toast.makeText(this, "City name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        City cleaned = new City(name, province);

        // Using city name as document ID (simple and matches your current lab style)
        citiesRef.document(cleaned.getName()).set(cleaned)
                .addOnFailureListener(e -> Log.e("Firestore", "Add failed", e));
    }

    @Override
    public void updateCity(City city, String newName, String newProvince) {
        if (city == null) return;

        String oldName = safeTrim(city.getName());
        String name = safeTrim(newName);
        String province = safeTrim(newProvince);

        if (name.isEmpty()) {
            Toast.makeText(this, "City name cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        City updated = new City(name, province);

        // If name (doc id) didn't change, overwrite the document
        if (oldName.equals(name)) {
            citiesRef.document(oldName).set(updated)
                    .addOnFailureListener(e -> Log.e("Firestore", "Update failed", e));
            return;
        }

        // If name changed, delete old doc then create new doc
        citiesRef.document(oldName).delete()
                .addOnSuccessListener(unused -> citiesRef.document(name).set(updated))
                .addOnFailureListener(e -> Log.e("Firestore", "Rename failed", e));
    }



    private void confirmAndDelete(City cityToDelete) {
        if (cityToDelete == null) return;

        new AlertDialog.Builder(this)
                .setTitle("Delete City")
                .setMessage("Delete " + cityToDelete.getName() + " (" + cityToDelete.getProvince() + ")?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteCityFromFirestore(cityToDelete))
                .show();
    }

    private void deleteCityFromFirestore(City city) {
        if (city == null) return;

        String name = safeTrim(city.getName());
        if (name.isEmpty()) return;

        citiesRef.document(name).delete()
                .addOnFailureListener(e -> Log.e("Firestore", "Delete failed", e));
        // DO NOT manually remove from cityArrayList.
        // Snapshot listener will refresh the ListView.
    }

    private String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
