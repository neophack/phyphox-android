package de.rwth_aachen.phyphox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import android.os.Environment;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

//The DataExport class provides export functionality for a phyphoxExperiment.
//it provides multiple export formats and the dialogs to control them
public class DataExport implements Serializable {

    //ExportSet class
    //An export set is a collection of related dataBuffers, which (ideally) have the same size
    //exportSets are defined for each experiments and represent logical subsets of all dataBuffers which the user might want to export
    public class ExportSet implements Serializable {
        String name;

        //This class maps dataBuffers (by their key name) to a name in this ExportSet
        protected class SourceMapping implements Serializable {
            String name;
            String source;

            SourceMapping(String name, String source) {
                this.name = name;
                this.source = source;
            }

            String getSecureName() {
                //Returns a sanitized version of name to prevent creating malicious data files through phyphox.

                //Prevent Excel formula injection (seriously, in case of csv, Excel should not be executing anyways, but let's protect the users...)
                if (name.startsWith("=") || name.startsWith("+") || name.startsWith("-") || name.startsWith("@")) {
                    return "'" + name;
                }

                return name;
            }
        }

        //The set consists of an arbitrary number of sourceMappings. So each entry in the dataSet has a name and a dataBuffer-source
        Vector<SourceMapping> sources = new Vector<>();

        //We will also hold an array with all the data. The idea is to let all dataSets collect their
        // data as fast as possible (like a snapshot) and then take care of pushing the data to an
        // exporter. So after instantiating an ExportSet (with a name), addSource is called for
        // each dataBuffer that should be added to the collection. Then getData is called so the
        // content of these buffers is collected and finally the dataSet is given to an instance
        // of ExportFormat (see below).
        Double[][] data;

        //constructor with name for this set
        ExportSet(String name) {
            this.name = name;
        }

        //Add dataBuffers with names to this set
        public void addSource(String name, String source) {
            this.sources.add(new SourceMapping(name, source));
        }

        //Retrieve all data from the dataBuffers
        public void getData() {
            data = new Double[sources.size()][];
            for (int i = 0; i < sources.size(); i++) {
                DataBuffer buffer = experiment.getBuffer(sources.get(i).source); //Get the buffer for this source
                data[i] = buffer.getArray(); //Get all data as a double array
            }
        }
    }

    private PhyphoxExperiment experiment; //The phyphoxExperiment which uses this DataExport
    public Vector<ExportSet> exportSets = new Vector<>(); //The available export sets

    //This abstract class defines the interface for a specific export format
    protected abstract class ExportFormat implements Serializable {
        protected String filenameBase = "phyphox";

        public void setFilenameBase (String fb) {
            filenameBase = fb;
        }

        protected abstract String getName(); //Returns the name or description of the format
        protected abstract File export (Vector<ExportSet> sets, File exportPath, boolean minimalistic, Context ctx); //The actual export routine, which returns a datafile
        protected abstract String getType(boolean minimalistic); //Returns the mime-type of the exported file.
        protected abstract String getFilename(boolean minimalistic); //Returns a default file name for the exported file
    }

    //Implements the CSV (Comma-separated values) format.
    //Despite its name you can change the separator to something mot practical (i.e. tab-separated)
    //To provite multiple datasets, the plain-text files are grouped into a single zip-file.
    protected class CsvFormat extends ExportFormat implements Serializable {
        protected char separator; //The separator, typically "," or "\t"
        protected char decimalPoint; //The separator, typically "," or "\t"
        protected String name; //The name of this format can be changed to describe different separators

        //This constructor allows to set a separator and a name
        CsvFormat(char separator, char decimalPoint, String name) {
            this.separator = separator;
            this.decimalPoint = decimalPoint;
            this.name = name;
        }

        //the default constructor uses a comma-separator (",") and an appropriate name
        CsvFormat() {
            this(',', '.', "Comma-separated values (CSV)");
        }

        @Override
        protected String getName() {
            return name;
        }

        @Override
        protected File export (Vector<ExportSet> sets, File exportPath, boolean minimalistic, Context ctx) {
            File file = new File(exportPath, "/"+getFilename(minimalistic)); // Create a file with default filename in the given path

            DecimalFormat format = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
//            format.applyPattern("0.000000000E0");
            format.applyPattern("0.################"); // 让小数部分尽量完整显示
            DecimalFormatSymbols dfs = format.getDecimalFormatSymbols();
            dfs.setDecimalSeparator(decimalPoint);
            format.setDecimalFormatSymbols(dfs);
            format.setGroupingUsed(false);

            try { // A lot can go wrong here... Let's catch em all...
                FileOutputStream stream = new FileOutputStream(file); //Open a basic output stream
                ZipOutputStream zstream = null;
                if (!minimalistic)
                    zstream = new ZipOutputStream(stream); //We will pack all datasets into a single zip
                try {
                    for (ExportSet set : sets) { // For each dataset...
                        ZipEntry entry;
                        if (!minimalistic) {
                            entry = new ZipEntry(set.name + ".csv"); //Create a new file for this dataset...
                            zstream.putNextEntry(entry); //...and add it to the zip-file
                        }

                        //Contruct the table header in the first line
                        String header = "";
                        for (int j = 0; j < set.data.length; j++) { //Each column gets a name...
                            header += "\"" + set.sources.get(j).getSecureName() + "\"";
                            if (j < set.data.length -1)
                                header += separator;
                        }
                        header += "\n";
                        if (minimalistic)
                            stream.write(header.getBytes());
                        else
                            zstream.write(header.getBytes()); //Write the header to the zip-file

                        //Then add all the data
                        for (int i = 0; i < set.data[0].length; i++) { //For each row of data... The first column determines the number of rows
                            //Construct the data row
                            StringBuilder data = new StringBuilder();
                            for (int j = 0; j < set.data.length; j++) { //For each column within this row
                                if (i < set.data[j].length) //Do we have data for this cell?
                                    data.append(format.format(set.data[j][i])); //Add it to the row
                                else
                                    data.append("NaN"); //No data? Enter NaN in the row
                                if (j < set.data.length - 1)
                                    data.append(separator);
                            }
                            data.append("\n");
                            if (minimalistic)
                                stream.write(data.toString().getBytes());
                            else
                                zstream.write(data.toString().getBytes()); //Write to zip-file
                        }

                        if (!minimalistic)
                            zstream.closeEntry(); //This dataset is complete. Close its file within the zip
                    }
                    //Add meta data in a separate folder
                    if (!minimalistic) {
                        ZipEntry entry;
                        entry = new ZipEntry("meta/device.csv");
                        zstream.putNextEntry(entry);
                        zstream.write(("\"property\""+separator+"\"value\"\n").getBytes());

                        StringBuilder data = new StringBuilder();
                        for (Metadata.DeviceMetadata deviceMetadata : Metadata.DeviceMetadata.values()) {
                            if (deviceMetadata == Metadata.DeviceMetadata.sensorMetadata || deviceMetadata == Metadata.DeviceMetadata.uniqueID || deviceMetadata == Metadata.DeviceMetadata.camera2api || deviceMetadata == Metadata.DeviceMetadata.camera2apiFull)
                                continue;
                            String identifier = deviceMetadata.toString();
                            data.append("\"").append(identifier).append("\"").append(separator);
                            data.append("\"").append(new Metadata(identifier, ctx).get("")).append("\"").append("\n");
                        }
                        for (SensorInput.SensorName sensor : SensorInput.SensorName.values()) {
                            for (Metadata.SensorMetadata sensorMetadata : Metadata.SensorMetadata.values()) {
                                String identifier = sensorMetadata.toString();
                                data.append("\"").append(sensor.name()).append(" ").append(identifier).append("\"").append(separator);
                                data.append("\"").append(new Metadata(sensor.name()+identifier, ctx).get("")).append("\"").append("\n");
                            }
                        }
                        zstream.write(data.toString().getBytes()); //Write to zip-file
                        zstream.closeEntry();

                        entry = new ZipEntry("meta/time.csv");
                        zstream.putNextEntry(entry);
                        zstream.write(("\"event\""+separator+"\"experiment time\""+separator+"\"system time\""+separator+"\"system time text\"\n").getBytes());

                        DecimalFormat longformat = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
                        longformat.applyPattern("############0.000");
                        longformat.setDecimalFormatSymbols(dfs);
                        longformat.setGroupingUsed(false);

                        data = new StringBuilder();
                        SimpleDateFormat dateFormat;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'XXX");
                        else
                            dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'Z");
                        for (ExperimentTimeReference.TimeMapping timeMapping : experiment.experimentTimeReference.timeMappings) {
                            data.append("\"").append(timeMapping.event.name()).append("\"").append(separator);
                            data.append(format.format(timeMapping.experimentTime)).append(separator);
                            data.append(longformat.format(timeMapping.systemTime/1000.)).append(separator);
                            data.append("\"").append(dateFormat.format(timeMapping.systemTime)).append("\"").append("\n");
                        }
                        zstream.write(data.toString().getBytes()); //Write to zip-file
                        zstream.closeEntry();
                    }
                } catch (Exception e) {
                    //This could be done better. Any error during CSV/ZIP compiling ends up here
                    Log.e("csvExport", "Unhandled exception during write.", e);
                } finally {
                    if (minimalistic)
                        stream.close();
                    else
                        zstream.close();
                }
            } catch (Exception e) {
                //This could be done better. Any error during file opening ends up here
                Log.e("csvExport", "Unhandled exception.", e);
            }

            return file;
        }

        @Override
        protected String getType (boolean minimalistic) {
            if (minimalistic)
                return "text/csv";
            else
                return "application/zip";
        }
        @Override
        protected String getFilename (boolean minimalistic) {
            if (minimalistic)
                return this.filenameBase + ".csv";
            else
                return this.filenameBase + ".zip";
        }
    }

    //This class implements an Microsoft Excel export using the Apache POI library
    protected class ExcelFormat extends ExportFormat implements Serializable {
        //Nothing to do or configure in the constructor
        ExcelFormat() {
        }

        @Override
        protected String getName() {
            return "Excel";
        }

        @Override
        protected File export (Vector<ExportSet> sets, File exportPath, boolean minimalistic, Context ctx) {
            File file = new File(exportPath, "/"+getFilename(minimalistic)); //Create file with default filename

            //New excel workbook
            Workbook wb = new HSSFWorkbook();
            //Create a style (just bold font) for the table header
            Font font= wb.createFont();
            font.setBold(true);
            CellStyle cs = wb.createCellStyle();
            cs.setFont(font);

            try { // A lot can go wrong here. Catch em all...
                for (ExportSet set : sets) { //For each dataset...
                    Sheet sheet = wb.createSheet(set.name);//..create a new sheet within the Excel document

                    //Create the header row and fill it
                    Row row = sheet.createRow(0);
                    for (int j = 0; j < set.data.length; j++) {
                        Cell c = row.createCell(j);
                        c.setCellValue(set.sources.get(j).getSecureName());
                        c.setCellStyle(cs);
                    }

                    //Create all the data rows
                    for (int i = 0; i < set.data[0].length; i++) { //For each row of data (number of rows determined by first entry in dataset)
                        row = sheet.createRow(i+1);
                        for (int j = 0; j < set.data.length; j++) { //For each column
                            Cell c = row.createCell(j);
                            if (i < set.data[j].length) //Is there data for this cell?
                                c.setCellValue(set.data[j][i]); //Yepp, enter it
                            else
                                c.setCellValue("NaN"); //Nope, no data. Fill NaN into this cell
                        }
                    }
                }

                if (!minimalistic) {
                    Sheet sheet;
                    Row row;
                    Cell c;

                    sheet = wb.createSheet("Metadata Device");//..create a new sheet within the Excel document
                    row = sheet.createRow(0);
                    c = row.createCell(0);
                    c.setCellValue("proeprty");
                    c.setCellStyle(cs);
                    c = row.createCell(1);
                    c.setCellValue("value");
                    c.setCellStyle(cs);

                    int i = 1;
                    StringBuilder data = new StringBuilder();
                    for (Metadata.DeviceMetadata deviceMetadata : Metadata.DeviceMetadata.values()) {
                        if (deviceMetadata == Metadata.DeviceMetadata.sensorMetadata || deviceMetadata == Metadata.DeviceMetadata.uniqueID || deviceMetadata == Metadata.DeviceMetadata.camera2api || deviceMetadata == Metadata.DeviceMetadata.camera2apiFull)
                            continue;
                        String identifier = deviceMetadata.toString();

                        row = sheet.createRow(i);
                        row.createCell(0).setCellValue(identifier);
                        row.createCell(1).setCellValue(new Metadata(identifier, ctx).get(""));
                        i++;
                    }
                    for (SensorInput.SensorName sensor : SensorInput.SensorName.values()) {
                        for (Metadata.SensorMetadata sensorMetadata : Metadata.SensorMetadata.values()) {
                            String identifier = sensorMetadata.toString();

                            row = sheet.createRow(i);
                            row.createCell(0).setCellValue(sensor.name() + " " + identifier);
                            row.createCell(1).setCellValue(new Metadata(sensor.name()+identifier, ctx).get(""));
                            i++;
                        }
                    }

                    sheet = wb.createSheet("Metadata Time");//..create a new sheet within the Excel document
                    row = sheet.createRow(0);
                    c = row.createCell(0);
                    c.setCellValue("event");
                    c.setCellStyle(cs);
                    c = row.createCell(1);
                    c.setCellValue("experiment time");
                    c.setCellStyle(cs);
                    c = row.createCell(2);
                    c.setCellValue("system time");
                    c.setCellStyle(cs);
                    c = row.createCell(3);
                    c.setCellValue("system time text");
                    c.setCellStyle(cs);

                    i = 1;
                    data = new StringBuilder();
                    SimpleDateFormat dateFormat;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'XXX");
                    else
                        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'Z");
                    for (ExperimentTimeReference.TimeMapping timeMapping : experiment.experimentTimeReference.timeMappings) {
                        row = sheet.createRow(i);
                        row.createCell(0).setCellValue(timeMapping.event.name());
                        row.createCell(1).setCellValue(timeMapping.experimentTime);
                        row.createCell(2).setCellValue(timeMapping.systemTime / 1000.);
                        row.createCell(3).setCellValue(dateFormat.format(timeMapping.systemTime));
                        i++;
                    }
                }

                //We now have our Excel document. Let's write it to the file.
                FileOutputStream os = null;
                try { //Let's catch errors while writing separately
                    os = new FileOutputStream(file);
                    wb.write(os);
                } catch (Exception e) {
                    Log.e("excelExport", "Unhandled exception during write.", e);
                } finally {
                    if (os != null)
                        os.close();
                }

            } catch (Exception e) {
                Log.e("excelExport", "Unhandled exception.", e);
            }

            return file;
        }

        @Override
        //This mime-typ is ugly, but seems to be the "official" one, while there are many others in use.
        protected String getType (boolean minimalistic) {
            return "application/vnd.ms-excel";
        }

        @Override
        protected String getFilename (boolean minimalistic) {
            return filenameBase + ".xls";
        }
    }

    //This array holds instances of all export formats that should be presented to the user
    public final ExportFormat[] exportFormats = {
            new ExcelFormat(),
            new CsvFormat(',', '.', "CSV (Comma, decimal point)"),
            new CsvFormat('\t', '.', "CSV (Tabulator, decimal point)"),
//            new CsvFormat(';', '.', "CSV (Semicolon, decimal point)"),
//            new CsvFormat('\t', ',', "CSV (Tabulator, decimal comma)"),
//            new CsvFormat(';', ',', "CSV (Semicolon, decimal comma)")
    };

    //The constructor just has to store a reference to the experiment
    DataExport(PhyphoxExperiment experiment) {
        this.experiment = experiment;
    }

    //Add an ExportSet to this exporter
    public void addSet(ExportSet set) {
        this.exportSets.add(set);
    }

    //Export the data (this will show dialogs to the user)
    public void export(Activity c, boolean minimalistic) {

        //Retrieve all the data
        for (int i = 0; i < exportSets.size(); i++) {
            exportSets.get(i).getData();
        }

        final String fileName = experiment.title.replaceAll("[^0-9a-zA-Z \\-_]", "");
        showFormatDialog(exportSets, c, minimalistic, fileName.isEmpty() ? "phyphox" : fileName);
    }

    public void saveData(Activity c, boolean minimalistic) {
        //Retrieve all the data
        for (int i = 0; i < exportSets.size(); i++) {
            exportSets.get(i).getData();
        }
        SharedPreferences prefs = c.getSharedPreferences("history_names", Context.MODE_PRIVATE);
        Set<String> nameSet = prefs.getStringSet("names", new HashSet<>());
        ArrayList<String> nameList = new ArrayList<>(nameSet);

        AlertDialog.Builder builder = new AlertDialog.Builder(c);
        builder.setTitle("输入或选择文件夹名");

        LayoutInflater inflater = c.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_name_input, null);
        builder.setView(dialogView);

        EditText inputField = dialogView.findViewById(R.id.edit_folder_name);
        ListView listView = dialogView.findViewById(R.id.list_folder_names);
        NameListAdapter adapter = new NameListAdapter(c, nameList, prefs);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            inputField.setText(nameList.get(position));
        });

        builder.setPositiveButton("确认", (dialog, which) -> {
            String fileName = inputField.getText().toString().trim().replaceAll("[^\\p{L}0-9a-zA-Z \\-_]", "");
            if (fileName.isEmpty()) {
                Toast.makeText(c, "文件夹名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            nameSet.add(fileName);
            prefs.edit().putStringSet("names", nameSet).apply();
            final String exprimentName = experiment.title.replaceAll("[^\\p{L}0-9a-zA-Z \\-_]", "");
            exportFormats[1].setFilenameBase((new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")).format(new Date()));

            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir != null) {
                File saveDirPhyphox = new File(documentsDir,"phyphox");
                File saveDirExp = new File(saveDirPhyphox,exprimentName);
                File saveDir = new File(saveDirExp, fileName);
                if (!saveDir.exists() && !saveDir.mkdirs()) {
                    Toast.makeText(c, "创建目录失败: " + saveDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                    return;
                }
                exportFormats[1].export(exportSets, saveDir, minimalistic, c);
                // 检查文件是否存在且大小不为 0
                File file = new File(saveDir, "/"+exportFormats[1].getFilename(minimalistic)); // Create a file with default filename in the given path
                if (file.isFile() && file.length() > 0) {
                    Toast.makeText(c, "保存成功：" + saveDir, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(c, "保存失败：文件为空", Toast.LENGTH_SHORT).show();
                }

            } else {
                Toast.makeText(c, "无法访问文档目录", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }


    //Annoying class to make the integer mutable.
    //The point is that we will show "radio buttons" when the user selects an export format. The
    //  result is a single index to the format selected by the user. So this should be an integer,
    //  which has to be mutable if we want to change it in the callback.
    protected class mutableInteger implements Serializable {
        public int value;
    }

    //Let the user select a fiile format. This takes a list of chosen sets as it is supposed to be
    //  called after the user has already chosen the sets to export.
    //If successfull it will trigger the actual export as a share intent
    protected void showFormatDialog(final Vector<ExportSet> chosenSets, final Activity c, final boolean minimalistic, final String fileName) {
        final mutableInteger selected = new mutableInteger(); //This will hold the result

        //Create the charsequences that should be presented to the user
        final CharSequence[] options = new CharSequence[exportFormats.length];
        for (int i = 0; i < exportFormats.length; i++) {
            options[i] = exportFormats[i].getName();
        }

        //Build the dialog...
        AlertDialog.Builder builder = new AlertDialog.Builder(c);
        builder.setTitle(R.string.pick_exportFormat) //Title from internationalization
                .setSingleChoiceItems(options, 0, //Callback if the user changes the selection
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                               selected.value = which; //Remember the selection
                            }
                        })
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) { //Callback when the user confirms his selection
                        //Lets do the actual export

                        //Set a file name including the current date
                        exportFormats[selected.value].setFilenameBase(fileName + " " + (new SimpleDateFormat("yyyy-MM-dd HH-mm-ss")).format(new Date()));

                        //Call the export filter to write the data to a file
                        File exportFile = exportFormats[selected.value].export(chosenSets, c.getCacheDir(), minimalistic, c);

                        //Use a FileProvider so we can send this file to other apps
                        final Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".exportProvider", exportFile);

                        //Create a share intent
                        final Intent intent = ShareCompat.IntentBuilder.from(c)
                                .setType(exportFormats[selected.value].getType(minimalistic)) //mime type from the export filter
                                .setSubject(c.getString(R.string.export_subject))
                                .setStream(uri)
                                .getIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        List<ResolveInfo> resInfoList = c.getPackageManager().queryIntentActivities(intent, 0);
                        for (ResolveInfo ri : resInfoList) {
                            c.grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }

                        //Create intents for apps that support viewing or editing the file
                        final Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                        viewIntent.setDataAndType(uri, exportFormats[selected.value].getType(minimalistic));
                        viewIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET | Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        resInfoList = c.getPackageManager().queryIntentActivities(viewIntent, 0);
                        Vector<Intent> extraIntents = new Vector<>();
                        for (ResolveInfo ri : resInfoList) {
                            if (ri.activityInfo.packageName.equals(BuildConfig.APPLICATION_ID
                            ))
                                continue;
                            Intent appIntent = new Intent();
                            appIntent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                            appIntent.setAction(Intent.ACTION_VIEW);
                            appIntent.setDataAndType(uri, exportFormats[selected.value].getType(minimalistic));
                            appIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            extraIntents.add(appIntent);
                            c.grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }

                        final Intent[] extraIntentsArray = extraIntents.toArray(new Intent[extraIntents.size()]);

                        //Create chooser
                        Intent chooser = Intent.createChooser(intent, c.getString(R.string.share_pick_share));
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntentsArray);

                        //And finally grant permissions again for any activities created by the chooser
                        resInfoList = c.getPackageManager().queryIntentActivities(chooser, 0);
                        for (ResolveInfo ri : resInfoList) {
                            if (ri.activityInfo.packageName.equals(BuildConfig.APPLICATION_ID
                            ))
                                continue;
                            c.grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }


                        //Execute this intent
                        c.startActivity(chooser);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {//Callback if the user aborts the dialog.
                        //Nothing to do here. We shall not export.
                    }
                });

        //Show the dialog which we have just built
        builder.create().show();
    }

    //This function allows to export the data without dialogs. Hence it takes a list of selected
    //   exportSets (as an array of their indices), the selected ExportFormat and the directory to
    //   write to.
    //This function is used when all the dialogs are not done in the app, but on the web interface.
    //The user will select the exportSets and file format in the browser and will download the
    //   resulting file there as well.
    protected File exportDirect(ExportFormat format, File cacheDir, boolean minimalistic, final String fileName, Context ctx) {
        for (int i = 0; i < exportSets.size(); i++) {
            exportSets.get(i).getData();
        }

        format.setFilenameBase(fileName + " " + (new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")).format(new Date()));

        return format.export(exportSets, cacheDir, minimalistic, ctx);
    }

}
