package io.multiversio.bench;
import static io.tileverse.parquetry.filter.Pred.col;
import io.tileverse.parquetry.data.ReadOptions;
import io.tileverse.parquetry.dataset.ParquetSource;
import io.tileverse.parquetry.filter.*;
import io.tileverse.parquetry.io.ByteRangeSource;
import io.tileverse.parquetry.record.ParquetRecord;
import io.tileverse.parquetry.schema.ColumnPath;
import io.tileverse.parquetry.tileverse.ByteRangeSources;
import io.tileverse.storage.RangeReader;
import io.tileverse.storage.Storage;
import io.tileverse.storage.s3.S3StorageProvider;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class ParquetryR2ComparableBench {
 public static void main(String[] a) throws Exception {
  String bucket=a[0], object=a[1]; Path workload=Path.of(a[2]); int warmups=Integer.parseInt(a[3]);
  URI endpoint=URI.create(a[4]); String region=a[5]; int limit=a.length==7?Integer.parseInt(a[6]):0;
  long t0=System.nanoTime();
  try(S3AsyncClient client=S3AsyncClient.builder().endpointOverride(endpoint).region(Region.of(region))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
        System.getenv("AWS_ACCESS_KEY_ID"),System.getenv("AWS_SECRET_ACCESS_KEY"))))
      .forcePathStyle(true).build();
      Storage storage=S3StorageProvider.open(URI.create("s3://"+bucket+"/"),client);
      RangeReader rr=storage.openRangeReader(object);
      ByteRangeSource bytes=ByteRangeSources.from(rr)) {
   ParquetSource ds=ParquetSource.open(bytes);
   System.err.printf(Locale.ROOT,"# init_ms=%.3f%n",(System.nanoTime()-t0)/1e6);
   List<String> lines=Files.readAllLines(workload);
   for(int i=0;i<warmups;i++) run(ds,lines,false,limit);
   run(ds,lines,true,limit);
  }
 }
 static void run(ParquetSource ds,List<String> lines,boolean emit,int limit) throws Exception {
  Projection projection=Projection.ofPhysical(List.of(ColumnPath.of("id"),ColumnPath.of("geometry"),ColumnPath.of("bbox")));
  int done=0;
  for(int i=1;i<lines.size();i++) {
   if(limit>0 && done>=limit) break;
   String[] p=lines.get(i).split(","); if(p.length<5) continue;
   Predicate pred=col("geometry").bboxIntersects(Bbox.of2d(Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Double.parseDouble(p[4])));
   long q0=System.nanoTime(), rows;
   try(Stream<ParquetRecord> s=ds.read(pred,projection,ReadOptions.DEFAULTS)){ rows=s.count(); }
   double ms=(System.nanoTime()-q0)/1e6;
   if(emit) System.out.printf(Locale.ROOT,"%s,%d,%.6f%n",p[0],rows,ms);
   done++;
  }
 }
}