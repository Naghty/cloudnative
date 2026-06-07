package com.duoc.cloudnative.service;

import com.duoc.cloudnative.model.GuiaDespacho;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GuiaService {

    @Autowired
    private S3Client s3Client;

    private final String EFS_PATH = "/mnt/efs/temporal/";
    private final String BUCKET_NAME = "mi-bucket-de-guias";
    private final List<GuiaDespacho> db = new ArrayList<>();

    public GuiaDespacho crearYGuardarTemporal(String numero, String transportista, MultipartFile file) throws IOException {
        File directory = new File(EFS_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String filename = numero + "_" + file.getOriginalFilename();
        String destinoEfs = EFS_PATH + filename;
        file.transferTo(new File(destinoEfs));

        GuiaDespacho guia = new GuiaDespacho();
        guia.setId(UUID.randomUUID().toString());
        guia.setNumeroGuia(numero);
        guia.setTransportista(transportista);
        guia.setFecha(LocalDate.now());
        guia.setRutaS3("");

        db.add(guia);
        return guia;
    }

    public void subirAWS(String id) throws IOException {
        GuiaDespacho guia = db.stream()
                .filter(g -> g.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Guia no encontrada"));

        String fechaFormato = guia.getFecha().toString().replace("-", "");
        String s3Key = fechaFormato + "/" + guia.getTransportista() + "/guia_" + guia.getNumeroGuia() + ".pdf";
        String filename = guia.getNumeroGuia() + "_guia.pdf";
        String localPath = EFS_PATH + filename;
        File file = new File(localPath);

        if (file.exists()) {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(s3Key)
                    .build(), RequestBody.fromFile(file));

            guia.setRutaS3(s3Key);
            file.delete();
        }
    }

    public byte[] descargarArchivo(String id) throws IOException {
        GuiaDespacho guia = db.stream()
                .filter(g -> g.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Guia no encontrada"));

        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(guia.getRutaS3())
                .build());
        
        return objectBytes.asByteArray();
    }

    public GuiaDespacho actualizar(String id, String nuevoTransportista) {
        GuiaDespacho guia = db.stream()
                .filter(g -> g.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No encontrada"));
        
        String fechaFormato = guia.getFecha().toString().replace("-", "");
        String viejaKey = guia.getRutaS3();
        
        guia.setTransportista(nuevoTransportista);
        String nuevaKey = fechaFormato + "/" + nuevoTransportista + "/guia_" + guia.getNumeroGuia() + ".pdf";
        
        if (viejaKey != null && !viejaKey.isEmpty()) {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(viejaKey)
                    .build());
            
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(nuevaKey)
                    .build(), RequestBody.fromBytes(objectBytes.asByteArray()));
            
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(viejaKey)
                    .build());
            
            guia.setRutaS3(nuevaKey);
        }
        
        return guia;
    }

    public void eliminar(String id) {
        GuiaDespacho guia = db.stream()
                .filter(g -> g.getId().equals(id))
                .findFirst()
                .orElse(null);
                
        if (guia != null) {
            if (guia.getRutaS3() != null && !guia.getRutaS3().isEmpty()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(guia.getRutaS3())
                        .build());
            }
            db.remove(guia);
        }
    }

    public List<GuiaDespacho> buscarPorFiltros(String transportista, LocalDate fecha) {
        return db.stream()
                .filter(g -> g.getTransportista().equalsIgnoreCase(transportista) && g.getFecha().equals(fecha))
                .collect(Collectors.toList());
    }
}