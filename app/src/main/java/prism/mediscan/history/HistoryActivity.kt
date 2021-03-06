package prism.mediscan.history

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.android.synthetic.main.activity_history.*
import kotlinx.android.synthetic.main.content_history.*
import kotlinx.coroutines.experimental.async
import prism.mediscan.*
import prism.mediscan.API.getInteractionsWithHistory
import prism.mediscan.details.PresentationDetails
import prism.mediscan.model.Interaction
import prism.mediscan.model.Presentation
import prism.mediscan.model.Scan


class HistoryActivity : AppCompatActivity() {
    var database: Database? = null
    var bdpm: BdpmDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setSupportActionBar(toolbar)
        setTitle(resources.getString(R.string.title_activity_history))

        database = Database(this)
        bdpm = BdpmDatabase(this)


        val values = database?.getAllScans()?.map { scan -> ScanListItem(bdpm!!, scan) };
        scan_history.adapter = HistoryAdapter(this, R.layout.scan_layout, values)
        scan_history.setOnItemClickListener { parent, view, position, id ->
            val item = parent.adapter.getItem(position) as ScanListItem
            goToDetails(item.presentation!!)
        }
        val history = getCisHistory()
        val that = this
        async {
            values?.forEach { it ->
                if (it.presentation != null)
                    it.presentation.specialite.interactions =
                            ArrayList<Interaction>(
                                    getInteractionsWithHistory(that,
                                            it.presentation.specialite.cis,
                                            history)
                            )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle item selection
        when (item.getItemId()) {
            R.id.remove_all -> {
                Log.d("HistoryActivity", "Remove all scans")
                removeAll()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun removeAll() {
        (scan_history.adapter as HistoryAdapter).clear()
        database?.removeAllScans()
    }

    fun goToDetails(presentation: Presentation) {
        try {
            presentation.specialite.interactions = ArrayList<Interaction>(getInteractionsWithHistory(this, presentation.specialite.cis, getCisHistory()))
            val intent = Intent(this, PresentationDetails::class.java)
            intent.putExtra(PRESENTATION, presentation)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("HistoryActivity", "Error trying to go to details", e);
        }
    }

    fun getCisHistory(): List<String> {
        val list = (scan_history.adapter as HistoryAdapter).values
                ?.map { it -> it.presentation!!.specialite.cis }
        return list ?: emptyList()
    }

    fun storeScan(presentation: Presentation) {
        val scan = database?.storeScan(Scan(presentation.cip13, System.currentTimeMillis(), presentation.dateExp, presentation.lot))
        if (scan != null && bdpm != null) {
            val item = ScanListItem(bdpm!!, scan)
            if (item.presentation != null)
                item.presentation.specialite.interactions = ArrayList<Interaction>(getInteractionsWithHistory(this, item.presentation.specialite.cis, getCisHistory()))
            (scan_history.adapter as HistoryAdapter).add(item)
        }
    }

    fun onScanSuccessful(presentation: Presentation) {
        this.storeScan(presentation);
        this.goToDetails(presentation);
    }

    fun getPresentationFromCip(cip: String): Presentation {
        val p = bdpm?.getPresentation(cip);
        if (p == null) throw Exception("Médicament inconnu")
        return p;
    }


    fun startScan(view: View) {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
        integrator.captureActivity = ScanActivity::class.java
        integrator.setBeepEnabled(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(this, "Cancelled", Toast.LENGTH_LONG).show()
            } else {
                try {
                    var code = result.contents;
                    var presentation: Presentation? = null;
                    // format du datamatrix page 7: http://www.ucdcip.org/pdf/CIP-ACL%20cahier%20n%C2%B01%20Data%20Matrix%20Tra%C3%A7abilit%C3%A9.pdf
                    /** 01 03400930000120 17 AAMMJJ 10 A11111
                     * sans espace
                     * Se lit
                     * 01 = 0 + code CIP13
                     * 17 = date de péremption
                     * 10 = numéro de lot
                     */
                    val regex = Regex("^.010(\\d{13})17(\\d{2})(\\d{2})(\\d{2})10([A-Z0-9]+)$")
                    if (regex.matches(result.contents)) {
                        val matched = regex.matchEntire(result.contents)
                        code = matched?.groups?.get(1)?.value;
                        val annee = matched?.groups?.get(2)?.value
                        val mois = matched?.groups?.get(3)?.value
                        val jour = matched?.groups?.get(4)?.value
                        if (code != null) {
                            presentation = this.getPresentationFromCip(code);
                            presentation.dateExp = "$mois/20$annee";
                            presentation.lot = matched?.groups?.get(5)?.value;
                        }
                    } else if (code != null)
                        presentation = this.getPresentationFromCip(code);
                    if (presentation != null)
                        this.onScanSuccessful(presentation);
                } catch (e: Exception) {
                    this.showError(e);
                    Log.e("MainActivity", "Parse scan result error: " + result.contents, e);
                }
            }
        } else {
            // This is important, otherwise the result will not be passed to the fragment
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun showError(e: Exception) {
        Toast.makeText(this, "Erreur: " + e.message, Toast.LENGTH_LONG).show();
    }
}
