package com.bright.maps.extractor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MapsExtractorApp() }
    }
}

data class PlaceResult(
    val id: String,
    val name: String,
    val category: String = "",
    val phone: String = "",
    val address: String = "",
    val website: String = "",
    val rating: Double? = null,
    val reviews: Int? = null,
    val mapsUrl: String = "",
    val lat: Double? = null,
    val lng: Double? = null
)

data class SearchHistory(val query: String, val location: String, val count: Int, val date: String)

enum class RunState { IDLE, RUNNING, PAUSED, FINISHED, ERROR }

class ExtractorViewModel(private val app: Context) : ViewModel() {
    var query by mutableStateOf("")
    var location by mutableStateOf("")
    var limit by mutableStateOf("60")
    var apiKey by mutableStateOf("")
    var state by mutableStateOf(RunState.IDLE)
    var results by mutableStateOf(listOf<PlaceResult>())
    var status by mutableStateOf("جاهز")
    var history by mutableStateOf(loadHistory(app))
    var minRating by mutableStateOf("")
    var phoneOnly by mutableStateOf(false)
    var websiteOnly by mutableStateOf(false)

    private val client = OkHttpClient()
    private var job: Job? = null
    private var nextPageToken: String? = null

    init { apiKey = app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("api_key", "") ?: "" }

    fun saveApiKey() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("api_key", apiKey.trim()).apply()
        status = "تم حفظ مفتاح API على الجهاز"
    }

    fun start() {
        if (query.isBlank() || location.isBlank()) { status = "اكتب كلمة البحث والمكان أولاً"; return }
        if (apiKey.isBlank()) { status = "أدخل Google Places API Key من الإعدادات"; return }
        val target = limit.toIntOrNull()?.coerceIn(1, 60) ?: 60
        results = emptyList(); nextPageToken = null
        state = RunState.RUNNING; status = "جاري الاتصال بـ Google Places…"
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive && results.size < target && state == RunState.RUNNING) {
                    val page = searchPlaces(query.trim() + " in " + location.trim(), nextPageToken)
                    val unique = LinkedHashMap<String, PlaceResult>()
                    results.forEach { unique[it.id] = it }
                    page.places.forEach { unique[it.id] = it }
                    withContext(Dispatchers.Main) {
                        results = unique.values.take(target)
                        status = "تم العثور على ${results.size} من $target"
                    }
                    nextPageToken = page.nextPageToken
                    if (nextPageToken.isNullOrBlank() || page.places.isEmpty()) break
                    delay(1200)
                }
                withContext(Dispatchers.Main) {
                    state = if (results.size >= target || nextPageToken.isNullOrBlank()) RunState.FINISHED else RunState.PAUSED
                    status = if (results.size >= target) "اكتمل الاستخراج" else "انتهت النتائج المتاحة لهذا البحث"
                    addHistory(app, SearchHistory(query.trim(), location.trim(), results.size, now()))
                    history = loadHistory(app)
                }
            } catch (e: CancellationException) { }
            catch (e: Exception) { withContext(Dispatchers.Main) { state = RunState.ERROR; status = "خطأ: ${e.message ?: "تعذر الاتصال"}" } }
        }
    }

    fun pause() { if (state == RunState.RUNNING) { state = RunState.PAUSED; status = "متوقف مؤقتًا — اضغط استكمال" } }
    fun resume() { if (state == RunState.PAUSED) { state = RunState.RUNNING; status = "جاري الاستكمال…"; continueJob() } }
    private fun continueJob() {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = limit.toIntOrNull()?.coerceIn(1, 60) ?: 60
                while (isActive && results.size < target && state == RunState.RUNNING) {
                    val page = searchPlaces(query.trim() + " in " + location.trim(), nextPageToken)
                    val unique = LinkedHashMap<String, PlaceResult>(); results.forEach { unique[it.id] = it }; page.places.forEach { unique[it.id] = it }
                    withContext(Dispatchers.Main) { results = unique.values.take(target); status = "تم العثور على ${results.size} من $target" }
                    nextPageToken = page.nextPageToken
                    if (nextPageToken.isNullOrBlank()) break
                    delay(1200)
                }
                withContext(Dispatchers.Main) { state = RunState.FINISHED; status = "اكتمل الاستخراج" }
            } catch (e: CancellationException) {} catch (e: Exception) { withContext(Dispatchers.Main) { state = RunState.ERROR; status = "خطأ: ${e.message}" } }
        }
    }
    fun stop() { job?.cancel(); state = RunState.FINISHED; status = "تم إيقاف العملية" }
    fun clear() { job?.cancel(); results = emptyList(); nextPageToken = null; state = RunState.IDLE; status = "جاهز" }

    fun filtered(): List<PlaceResult> = results.filter {
        (!phoneOnly || it.phone.isNotBlank()) && (!websiteOnly || it.website.isNotBlank()) &&
            (minRating.toDoubleOrNull()?.let { min -> (it.rating ?: 0.0) >= min } ?: true)
    }

    private data class Page(val places: List<PlaceResult>, val nextPageToken: String?)
    private fun searchPlaces(text: String, pageToken: String?): Page {
        val body = JSONObject().put("textQuery", text).put("maxResultCount", 20)
        if (!pageToken.isNullOrBlank()) body.put("pageToken", pageToken)
        val req = Request.Builder().url("https://places.googleapis.com/v1/places:searchText")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Key", apiKey.trim())
            .addHeader("X-Goog-FieldMask", "places.id,places.displayName,places.types,places.formattedAddress,places.nationalPhoneNumber,places.websiteUri,places.rating,places.userRatingCount,places.googleMapsUri,places.location,nextPageToken")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { raw.take(120) }}")
            val json = JSONObject(raw); val arr = json.optJSONArray("places") ?: JSONArray(); val out = mutableListOf<PlaceResult>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i); val display = p.optJSONObject("displayName")?.optString("text").orEmpty(); val loc = p.optJSONObject("location")
                val types = p.optJSONArray("types"); val category = if (types != null && types.length() > 0) types.optString(0) else ""
                out += PlaceResult(p.optString("id"), display, category, p.optString("nationalPhoneNumber"), p.optString("formattedAddress"), p.optString("websiteUri"), if (p.has("rating")) p.optDouble("rating") else null, if (p.has("userRatingCount")) p.optInt("userRatingCount") else null, p.optString("googleMapsUri"), loc?.optDouble("latitude"), loc?.optDouble("longitude"))
            }
            return Page(out, json.optString("nextPageToken").takeIf { it.isNotBlank() })
        }
    }
}

@Composable fun MapsExtractorApp(vm: ExtractorViewModel = viewModel(factory = androidx.lifecycle.viewmodel.initializer { ExtractorViewModel(LocalContext.current.applicationContext) })) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var exportType by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        if (uri != null && exportType != null) Exporter.export(context, uri, exportType!!, vm.filtered())
    }
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Google Maps Data Extractor") }, actions = { TextButton(onClick = { showSettings = true }) { Text("الإعدادات") } }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                OutlinedTextField(vm.query, { vm.query = it }, label = { Text("كلمة البحث") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp)); OutlinedTextField(vm.location, { vm.location = it }, label = { Text("الدولة / المدينة / المنطقة") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp)); OutlinedTextField(vm.limit, { vm.limit = it }, label = { Text("عدد النتائج (حتى 60 لكل بحث)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.start() }, enabled = vm.state != RunState.RUNNING) { Text("بدء الاستخراج") }
                    if (vm.state == RunState.RUNNING) OutlinedButton(onClick = vm.pause) { Text("إيقاف مؤقت") }
                    if (vm.state == RunState.PAUSED) OutlinedButton(onClick = vm.resume) { Text("استكمال") }
                    if (vm.state == RunState.RUNNING || vm.state == RunState.PAUSED) OutlinedButton(onClick = vm.stop) { Text("إيقاف") }
                }
                Spacer(Modifier.height(10.dp)); Text(vm.status); Spacer(Modifier.height(6.dp))
                val target = vm.limit.toIntOrNull()?.coerceAtLeast(1) ?: 60
                LinearProgressIndicator(progress = { (vm.results.size.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("النتائج: ${vm.filtered().size}/${vm.results.size}")
                    OutlinedButton(onClick = { vm.clear() }) { Text("مسح") }
                    OutlinedButton(onClick = { exportType = "csv"; launcher.launch("google_maps_results.csv") }) { Text("CSV") }
                    OutlinedButton(onClick = { exportType = "xlsx"; launcher.launch("google_maps_results.xlsx") }) { Text("Excel") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vm.minRating, { vm.minRating = it }, label = { Text("أقل تقييم") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = vm.phoneOnly, onClick = { vm.phoneOnly = !vm.phoneOnly }, label = { Text("هاتف") })
                    FilterChip(selected = vm.websiteOnly, onClick = { vm.websiteOnly = !vm.websiteOnly }, label = { Text("موقع") })
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.filtered(), key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(r.name, style = MaterialTheme.typography.titleMedium); if (r.category.isNotBlank()) Text(r.category); if (r.address.isNotBlank()) Text(r.address); if (r.phone.isNotBlank()) Text(r.phone); if (r.rating != null) Text("★ ${r.rating} (${r.reviews ?: 0})") } }
                    }
                }
            }
        }
        if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("إعدادات Google Places") }, text = {
            Column { OutlinedTextField(vm.apiKey, { vm.apiKey = it }, label = { Text("Google Places API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); Text("المفتاح يُحفظ محليًا على الهاتف. استخدم مفتاحًا مقيّدًا للمشروع والتطبيق.") }
        }, confirmButton = { Button(onClick = { vm.saveApiKey(); showSettings = false }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = { showSettings = false }) { Text("إلغاء") } })
    }
}

object Exporter {
    fun export(context: Context, uri: Uri, type: String, rows: List<PlaceResult>) {
        context.contentResolver.openOutputStream(uri)?.use { out -> if (type == "csv") writeCsv(out, rows) else writeXlsx(out, rows) }
    }
    private fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun writeCsv(out: java.io.OutputStream, rows: List<PlaceResult>) {
        val sb = StringBuilder("ID,Name,Category,Phone,Address,Website,Rating,Reviews,Google Maps URL,Latitude,Longitude\n")
        rows.forEach { r -> sb.append(listOf(r.id,r.name,r.category,r.phone,r.address,r.website,r.rating?.toString().orEmpty(),r.reviews?.toString().orEmpty(),r.mapsUrl,r.lat?.toString().orEmpty(),r.lng?.toString().orEmpty()).joinToString(",") { esc(it) }).append('\n') }
        out.write(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())); out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun writeXlsx(out: java.io.OutputStream, rows: List<PlaceResult>) {
        val headers = listOf("ID","Name","Category","Phone","Address","Website","Rating","Reviews","Google Maps URL","Latitude","Longitude")
        val data = listOf(headers) + rows.map { listOf(it.id,it.name,it.category,it.phone,it.address,it.website,it.rating?.toString().orEmpty(),it.reviews?.toString().orEmpty(),it.mapsUrl,it.lat?.toString().orEmpty(),it.lng?.toString().orEmpty()) }
        ZipOutputStream(out).use { z ->
            fun put(path: String, content: String) { z.putNextEntry(ZipEntry(path)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Results\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            val sheet = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            data.forEachIndexed { ri, row -> sheet.append("<row r=\"${ri+1}\">"); row.forEachIndexed { ci, v -> val ref = col(ci)+(ri+1); sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xml(v)}</t></is></c>") }; sheet.append("</row>") }
            sheet.append("</sheetData></worksheet>"); put("xl/worksheets/sheet1.xml", sheet.toString())
        }
    }
    private fun col(i: Int): String { var n=i+1; var s=""; while(n>0){ val r=(n-1)%26; s=('A'.code+r).toChar()+s; n=(n-1)/26 }; return s }
}

private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
private fun loadHistory(context: Context): List<SearchHistory> = runCatching {
    val raw = context.getSharedPreferences("history", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"; val a=JSONArray(raw)
    List(a.length()) { i -> val o=a.getJSONObject(i); SearchHistory(o.optString("q"),o.optString("l"),o.optInt("c"),o.optString("d")) }
}.getOrDefault(emptyList())
private fun addHistory(context: Context, h: SearchHistory) { val list=(listOf(h)+loadHistory(context)).take(20); val a=JSONArray(); list.forEach { a.put(JSONObject().put("q",it.query).put("l",it.location).put("c",it.count).put("d",it.date)) }; context.getSharedPreferences("history", Context.MODE_PRIVATE).edit().putString("items",a.toString()).apply() }
